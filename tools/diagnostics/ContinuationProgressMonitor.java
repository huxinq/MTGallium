import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import kotlinx.serialization.builtins.BuiltinSerializersKt;
import kotlin.jvm.internal.StringCompanionObject;
import kotlinx.serialization.json.*;
import org.mtgallium.agent.infoset.core.ComponentSeeds;
import org.mtgallium.evaluation.searchteacher.*;
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore;
import org.mtgallium.research.run.*;

/** Passive monitor. Never invokes gameplay or changes a treatment/output artifact. */
public class ContinuationProgressMonitor {
    static final Json JSON = SearchTeacherSupportKt.getEvidenceJson();
    static final String FORECAST_LIMIT = "Conditional plug-in forecast: future independent paired scores are sampled from the observed pair-score distribution. The interval models future outcome variation, not uncertainty in that fitted distribution. Any test stop includes futility and budget exhaustion. Games are counted from the last verified batch, including in-flight work. This does not estimate games guaranteed to prove superiority, cost-gate success or non-game interruptions.";
    record Row(PairedSequentialScore score, int wins, int losses, int draws) {}

    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return JSON.encodeToString(BuiltinSerializersKt.serializer(StringCompanionObject.INSTANCE), s);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> m) {
            var entries = new ArrayList<String>();
            m.entrySet().stream().sorted(Comparator.comparing(e->e.getKey().toString())).forEach(e -> entries.add(json(e.getKey().toString())+":"+json(e.getValue())));
            return "{"+String.join(",",entries)+"}";
        }
        if (value instanceof Collection<?> list) return "["+String.join(",",list.stream().map(ContinuationProgressMonitor::json).toList())+"]";
        throw new IllegalArgumentException("Unsupported JSON value "+value.getClass());
    }
    static JsonObject object(Path path) throws Exception { return (JsonObject)JSON.parseToJsonElement(Files.readString(path)); }
    static String string(JsonObject o, String key) { return ((JsonPrimitive)o.get(key)).getContent(); }
    static void check(boolean value, String why) { if (!value) throw new IllegalStateException(why); }
    static boolean terminalExecution(String state) { return List.of("succeeded","failed").contains(state); }
    static ResearchRunBindings snapshotBindings(String setup, List<Map<String,Object>> inputs, String prefix, int executedPairs) {
        return new ResearchRunBindings(1,"continuation-progress-snapshot-v1",Map.of("setup",setup,"inputs",ResearchRunKt.researchSha256(json(inputs)),"prefix",prefix,"executed-pairs",Integer.toString(executedPairs)));
    }
    static void write(Path path, Object value) { ResearchRunFiles.INSTANCE.atomicWrite(path,json(value)+"\n"); }

    /** Calls the actual treatment stopping rule on every simulated future path. */
    static Map<String,Object> forecast(PairedSequentialRule rule, List<PairedSequentialScore> prefix,
            int first, int workers, int trials, long seed) {
        check(trials > 0 && workers > 0,"forecast configuration");
        var current = PairedSequentialTestKt.pairedSequentialTest(rule,prefix,first);
        check(!prefix.isEmpty() && prefix.stream().allMatch(s -> s.getPointRate()!=null),"forecast requires valid scores");
        var stops = new TreeMap<String,Integer>();
        var inspected = new ArrayList<Integer>(); var executed = new ArrayList<Integer>();
        int remaining = rule.getMaximumPairs()-prefix.size();
        for (int trial=0;trial<trials;trial++) {
            var path = new ArrayList<PairedSequentialScore>(prefix); var random = new SplittableRandom(seed+trial);
            if (current.getDisposition()==PairedSequentialDisposition.CONTINUE) {
                for (int i=prefix.size();i<rule.getMaximumPairs();i++)
                    path.add(new PairedSequentialScore(first+i,prefix.get(random.nextInt(prefix.size())).getPointRate(),List.of()));
            }
            var result = PairedSequentialTestKt.pairedSequentialTest(rule,path,first);
            int needed = Math.max(0,result.getInspectedPairs()-prefix.size());
            inspected.add(2*needed);
            executed.add(2*Math.min(remaining,((needed+workers-1)/workers)*workers));
            stops.merge(result.getDisposition().name(),1,Integer::sum);
        }
        inspected.sort(Integer::compare);executed.sort(Integer::compare);
        double mean = executed.stream().mapToInt(i->i).average().orElseThrow();
        double variance = executed.stream().mapToDouble(i -> (i-mean)*(i-mean)).sum()/Math.max(1,trials-1);
        var out = new LinkedHashMap<String,Object>();
        out.put("trials",trials);out.put("seed",seed);
        out.put("expectedAdditionalExecutedGames",mean);
        out.put("expectedAdditionalInspectedGames",inspected.stream().mapToInt(i->i).average().orElseThrow());
        out.put("additionalExecutedGamesP05",executed.get((int)Math.floor(.05*(trials-1))));
        out.put("additionalExecutedGamesP95",executed.get((int)Math.ceil(.95*(trials-1))));
        out.put("meanMonteCarloSEGames",Math.sqrt(variance/trials));
        out.put("upperOnlyStopProbability",stops.getOrDefault("ABOVE_NULL",0)/(double)trials);
        out.put("stopDispositions",stops);out.put("assumptions",FORECAST_LIMIT);
        return out;
    }

    static List<Row> rows(List<SearchBudgetFrontierPair> pairs,String candidate) {
        var rows = new ArrayList<Row>();
        for (var pair:pairs) {
            int wins=0,losses=0,draws=0;
            if (pair.getValid()) for (String seat:List.of("p0","p1")) {
                var summary=SearchBudgetFrontierKt.searchBudgetFrontierSeat(seat,List.of(pair),candidate);
                wins+=summary.getWins();losses+=summary.getLosses();draws+=summary.getDraws();
            }
            rows.add(new Row(new PairedSequentialScore(pair.getPairIndex(),pair.getValid()?pair.getTreatmentPoints()/2.0:null,pair.getInvalidationReasons()),wins,losses,draws));
        }
        return rows;
    }

    static class Monitor {
        final Path setup,game,output,state;final String setupIdentity,unit;
        final JsonObject config;final SearchTeacherContinuationPlan plan;final SearchTeacherCalibrationPlan parentPlan;
        final PairedSequentialRule rule;final int oldPairs,first,workers,trials;
        final ResearchSourceProvenance parentSource;final String deckHash,cardPoolHash;final List<String> behaviorHashes;
        final List<Row> rows=new ArrayList<>();final List<Map<String,Object>> inputs=new ArrayList<>();
        final Map<Path,String> loaded=new LinkedHashMap<>();
        Map<String,Object> snapshot;String snapshotIdentity;
        Monitor(Path setup) throws Exception {
            this.setup=setup;
            var manifest=ResearchRunArtifacts.Companion.loadAndVerify(setup,null);setupIdentity=manifest.getResearchRunIdentity();
            config=object(setup.resolve("config.json"));game=Path.of(string(config,"gameDirectory"));state=Path.of(string(config,"durableStateDirectory"));unit=string(config,"unit");
            Path repository=Path.of(string(config,"treatmentRepository"));
            output=new EvidenceStore(repository).requireDiagnosticOutput(Path.of(string(config,"outputDirectory")),"Automated continuation monitoring");
            check(!output.startsWith(game)&&!game.startsWith(output),"monitor must be separate from gameplay");
            String classpath=Files.readString(setup.resolve("classpath.txt")).trim();
            check(System.getProperty("java.class.path").equals(setup.resolve("classes")+":"+classpath),"unexpected monitor runtime");
            plan=JSON.decodeFromString(SearchTeacherContinuationPlan.Companion.serializer(),Files.readString(setup.resolve("continuation-plan.json")));
            check(Files.readString(game.resolve("plan.json")).equals(Files.readString(setup.resolve("continuation-plan.json"))),"game plan differs from sealed monitor setup");
            check(plan.getExpectedSourceCommit().equals(string(config,"treatmentSource")),"treatment source");
            ResearchRunArtifacts.Companion.loadAndVerify(Path.of(plan.getBuild().getDirectory()),plan.getBuild().getIdentity());
            check(ResearchRunKt.researchSha256File(Path.of(plan.getBuild().getDirectory()).resolve("research-run-manifest.json")).equals(plan.getBuild().getManifestSha256()),"build manifest");
            check(classpath.equals(Files.readString(Path.of(plan.getBuild().getDirectory()).resolve("classpath.txt")).trim()),"frozen treatment classpath");
            Path parent=Path.of(plan.getParentDirectory());
            check(ResearchRunKt.researchSha256File(parent.resolve("research-run-manifest.json")).equals(plan.getParentManifestSha256()),"parent manifest");
            var report=RealGamePositionBankKt.loadCompletedSequentialCalibration(parent,plan.getParentIdentity());
            parentSource=report.getSourceProvenance();deckHash=report.getDeckHash();cardPoolHash=report.getCardPoolHash();
            behaviorHashes=report.getPolicies().stream().map(p->p.getBinding().getBehaviorSpecificationSha256()).toList();
            parentPlan=report.getPlan();first=parentPlan.getPairOffset();workers=plan.getWorkerThreads();trials=Integer.parseInt(string(config,"trials"));
            rows.addAll(rows(RealGamePositionBankKt.completedSequentialBankPairs(report),parentPlan.getCandidates().get(0).getId()));oldPairs=rows.size();
            var scores=rows.stream().map(Row::score).toList();
            rule=SearchTeacherContinuationKt.continuationRule(report.getSequentialRule(),report.getSequentialResult(),scores,first,plan.getTotalPairCap());
            check(report.getSequentialResult().getOrderedPrefixSha256().equals(plan.getParentPrefixSha256()),"parent prefix");
            inputs.add(Map.of("directory",parent.toString(),"identity",plan.getParentIdentity(),"manifestSha256",plan.getParentManifestSha256(),"source",report.getSourceProvenance().getOuter().getRevision()));
        }
        boolean refresh() throws Exception {
            check(Files.readString(game.resolve("plan.json")).equals(Files.readString(setup.resolve("continuation-plan.json"))),"game plan changed");
            var chunks=new ArrayList<Path>();Path root=game.resolve("chunks");
            if(Files.exists(root))try(var paths=Files.list(root)){paths.filter(p->Files.exists(p.resolve("research-run-manifest.json"))).forEach(chunks::add);}
            chunks.sort(Comparator.comparingInt(p->Integer.parseInt(p.getFileName().toString().substring(5))));
            boolean changed=snapshot==null||(int)snapshot.get("newCompletedPairs")!=rows.size()-oldPairs;
            Path announcedPath=game.resolve("progress.json");
            PairedSequentialResult announced=Files.exists(announcedPath)?JSON.decodeFromString(PairedSequentialResult.Companion.serializer(),Files.readString(announcedPath)):null;
            int admitted=announced==null?oldPairs:announced.getInspectedPairs()+announced.getOperationalOvershootPairs();
            for(Path chunk:chunks) {
                String hash=ResearchRunKt.researchSha256File(chunk.resolve("research-run-manifest.json"));
                if(loaded.containsKey(chunk)){check(loaded.get(chunk).equals(hash),"finalized chunk manifest changed");continue;}
                if(rows.size()>=admitted)break;
                check(SearchTeacherContinuationKt.continuationChunk(rule,rows.stream().map(Row::score).toList(),first,workers)!=null,"unexpected batch after cumulative stop");
                var report=SearchTeacherSupportKt.readEvidenceJson(chunk.resolve("report.json"),SearchTeacherCalibrationReport.Companion.serializer());
                RealGamePositionBankKt.verifyCompletedCalibration(chunk,report.getRunIdentity());
                check(report.getPlan().getPairOffset()==first+rows.size(),"noncontiguous chunk");
                check(report.getWorkerThreads()==workers&&report.getPlan().getPairCount()==Math.min(workers,rule.getMaximumPairs()-rows.size()),"chunk population");
                check(report.getPlan().getBaseSeed()==parentPlan.getBaseSeed()&&report.getPlan().getControl().equals(parentPlan.getControl())&&report.getPlan().getCandidates().equals(parentPlan.getCandidates()),"chunk configuration");
                check(report.getSourceProvenance().getOuter().getRevision().equals(plan.getExpectedSourceCommit()),"chunk source");
                check(report.getSourceProvenance().getArgentum().equals(parentSource.getArgentum())&&report.getDeckHash().equals(deckHash)&&report.getCardPoolHash().equals(cardPoolHash),"engine/deck continuity");
                check(report.getPlan().getPhase()==parentPlan.getPhase()&&report.getPolicies().stream().map(p->p.getBinding().getBehaviorSpecificationSha256()).toList().equals(behaviorHashes),"policy behavior continuity");
                rows.addAll(rows(report.getComparisons().get(0).getPairs(),parentPlan.getCandidates().get(0).getId()));
                loaded.put(chunk,hash);inputs.add(Map.of("directory",chunk.toString(),"identity",report.getRunIdentity(),"manifestSha256",hash,"source",report.getSourceProvenance().getOuter().getRevision()));changed=true;
            }
            check(chunks.containsAll(loaded.keySet()),"completed chunk disappeared");
            check(rows.size()==admitted,"incomplete announced population");
            if(announced!=null)check(PairedSequentialTestKt.pairedSequentialTest(rule,rows.stream().map(Row::score).toList(),first).equals(announced),"coordinator progress replay");
            if(changed) makeSnapshot();
            return changed;
        }
        void makeSnapshot() throws Exception {
            var scores=rows.stream().map(Row::score).toList();var result=PairedSequentialTestKt.pairedSequentialTest(rule,scores,first);
            var prefix=rows.subList(0,result.getInspectedPairs());int wins=0,losses=0,draws=0;
            var boot=new ArrayList<TournamentPairIndexScore>();
            for(var row:prefix){wins+=row.wins;losses+=row.losses;draws+=row.draws;if(row.score.getPointRate()!=null)boot.add(new TournamentPairIndexScore(row.score.getPairIndex(),row.wins/2.0));}
            var ci=TournamentV3CalibratedKt.pairIndexBootstrapInterval(boot,ComponentSeeds.INSTANCE.derive(new Object[]{parentPlan.getBaseSeed(),"search-teacher-calibration-bootstrap-v1"}),10000);
            var data=new LinkedHashMap<String,Object>();
            data.put("capturedAt",Instant.now().toString());data.put("monitorSource",string(config,"monitorSource"));data.put("treatmentSource",plan.getExpectedSourceCommit());
            data.put("expectedArgentumRevision",parentSource.getExpectedArgentumRevision());data.put("checkedOutArgentumRevision",parentSource.getArgentum().getRevision());
            data.put("inputs",List.copyOf(inputs));data.put("parentPairs",oldPairs);data.put("newCompletedPairs",rows.size()-oldPairs);data.put("inspectedPairs",result.getInspectedPairs());
            data.put("validPairs",result.getValidScoredPairs());data.put("wins",wins);data.put("losses",losses);data.put("draws",draws);
            data.put("winRate",wins/(double)(wins+losses+draws));data.put("pointRate",prefix.stream().filter(r->r.score.getPointRate()!=null).mapToDouble(r->r.score.getPointRate()).average().orElseThrow());
            data.put("pairedWinRate95Lower",ci.getFirst());data.put("pairedWinRate95Upper",ci.getSecond());
            data.put("ciMeaning","Descriptive 95% pair-bootstrap win-rate interval; draws are reported separately. Not an anytime confidence sequence.");
            data.put("disposition",result.getDisposition().name());data.put("overshootPairs",result.getOperationalOvershootPairs());data.put("invalidReasons",result.getInvalidReasons());
            data.put("remainingPairCap",rule.getMaximumPairs()-rows.size());
            if(scores.stream().allMatch(s->s.getPointRate()!=null))data.put("forecast",forecast(rule,scores,first,workers,trials,Long.parseUnsignedLong(result.getOrderedPrefixSha256().substring(0,16),16)));
            else data.put("forecast",null);
            var bindings=snapshotBindings(setupIdentity,inputs,result.getOrderedPrefixSha256(),rows.size());
            snapshotIdentity=bindings.getIdentity();Path dest=output.resolve("snapshots").resolve(snapshotIdentity.substring(snapshotIdentity.lastIndexOf(':')+1));
            if(Files.exists(dest.resolve("research-run-manifest.json"))) {
                ResearchRunArtifacts.Companion.loadAndVerify(dest,snapshotIdentity);
                // Keep the original creation time; recomputation is a consistency check, not new evidence.
                data.put("capturedAt",string(object(dest.resolve("report.json")),"capturedAt"));
                check(Files.readString(dest.resolve("report.json")).equals(json(data)+"\n"),"snapshot replay differs");
            } else {
                write(dest.resolve("report.json"),data);write(dest.resolve("bindings.json"),Map.of("identity",snapshotIdentity,"material",bindings.getMaterial()));
                var artifacts=new ResearchRunArtifacts(dest,snapshotIdentity);artifacts.register("report.json");artifacts.register("bindings.json");artifacts.finalize();
                ResearchRunArtifacts.Companion.loadAndVerify(dest,snapshotIdentity);
            }
            snapshot=data;
        }
        boolean publish(String error) throws Exception {
            var health=new LinkedHashMap<String,Object>();
            var process=new ProcessBuilder("systemctl","--user","show",unit,"-p","ActiveState","-p","SubState","-p","ExecMainStatus").redirectErrorStream(true).start();
            boolean finished=process.waitFor(5,TimeUnit.SECONDS);
            if(!finished)process.destroyForcibly();
            health.put("querySucceeded",finished&&process.exitValue()==0);
            if(finished) for(String line:new String(process.getInputStream().readAllBytes()).split("\n")) {
                int equal=line.indexOf('=');if(equal>0)health.put(line.substring(0,equal),line.substring(equal+1));
            }
            var status=object(state.resolve("status.json"));String experiment=string((JsonObject)status.get("experiment"),"state");
            health.put("experimentState",experiment);
            Path progressPath=state.resolve("progress.json");
            if(Files.exists(progressPath))health.put("lastWorkloadProgress",string(object(progressPath),"detail"));
            var latest=new LinkedHashMap<String,Object>();latest.put("updatedAt",Instant.now().toString());
            latest.put("snapshotIdentity",snapshotIdentity);latest.put("snapshot",snapshot);latest.put("runHealth",health);latest.put("monitorError",error);
            boolean terminal=terminalExecution(experiment);
            latest.put("terminal",terminal);write(output.resolve("latest.json"),latest);
            StringBuilder text=new StringBuilder("# Continuation progress\n\nUpdated "+latest.get("updatedAt")+"\n\n");
            text.append("Run: ").append(experiment).append("; service: ").append(health.getOrDefault("ActiveState","unknown")).append("/" ).append(health.getOrDefault("SubState","unknown")).append(".\n\n");
            text.append("Workload report: ").append(health.getOrDefault("lastWorkloadProgress","unavailable")).append(".\n\n");
            if(error!=null)text.append("Monitor error: ").append(error).append(". Statistics below are the last verified snapshot.\n\n");
            if(snapshot!=null) {
                text.append(String.format(Locale.ROOT,"%s new pairs completed; %s cumulative pairs inspected. %s wins / %s losses / %s draws.\n\nWR %.1f%%; descriptive 95%% paired CI %.1f%%–%.1f%%. Test: %s.\n\n",snapshot.get("newCompletedPairs"),snapshot.get("inspectedPairs"),snapshot.get("wins"),snapshot.get("losses"),snapshot.get("draws"),100*(double)snapshot.get("winRate"),100*(double)snapshot.get("pairedWinRate95Lower"),100*(double)snapshot.get("pairedWinRate95Upper"),snapshot.get("disposition")));
                @SuppressWarnings("unchecked") var f=(Map<String,Object>)snapshot.get("forecast");
                if(f!=null&&!terminal&&error==null&&"active".equals(health.get("ActiveState")))text.append(String.format(Locale.ROOT,"Estimated additional games to any test stop: %.0f (modelled 90%% range %s–%s). Estimated upper-only stop probability: %.1f%%. Remaining hard cap: %d games.\n\n",(double)f.get("expectedAdditionalExecutedGames"),f.get("additionalExecutedGamesP05"),f.get("additionalExecutedGamesP95"),100*(double)f.get("upperOnlyStopProbability"),2*(int)snapshot.get("remainingPairCap")));
                if(terminal)text.append("Execution has stopped; no remaining-game forecast is applicable.\n\n");
                text.append(FORECAST_LIMIT).append("\n\nSnapshot: ").append(snapshotIdentity).append(".\n");
            }
            ResearchRunFiles.INSTANCE.atomicWrite(output.resolve("latest.md"),text.toString());
            return terminal;
        }
    }
    static void sealSetup(Path setup) throws Exception {
        var config=object(setup.resolve("config.json"));
        new EvidenceStore(Path.of(string(config,"treatmentRepository"))).requireDiagnosticOutput(setup,"Monitor setup");
        check(!Files.exists(setup.resolve("research-run-manifest.json")),"setup already sealed");
        var hashes=new TreeMap<String,String>();
        try(var files=Files.walk(setup)){for(Path f:files.filter(Files::isRegularFile).sorted().toList())hashes.put(setup.relativize(f).toString(),ResearchRunKt.researchSha256File(f));}
        var binding=new ResearchRunBindings(1,"continuation-progress-monitor-setup-v1",Map.of("files",ResearchRunKt.researchSha256(json(hashes)),"source",string(config,"monitorSource")));
        var artifacts=new ResearchRunArtifacts(setup,binding.getIdentity());for(String file:hashes.keySet())artifacts.register(file);artifacts.finalize();
        ResearchRunArtifacts.Companion.loadAndVerify(setup,binding.getIdentity());System.out.println(binding.getIdentity());
    }
    public static void main(String[] args) throws Exception {
        if(args.length==2&&args[0].equals("--seal-setup")){sealSetup(Path.of(args[1]));return;}
        check(args.length==1||args.length==2,"Expected setup directory and optional --once");
        boolean once=args.length==2;check(!once||args[1].equals("--once"),"unknown option");
        var monitor=new Monitor(Path.of(args[0]));int seconds=Integer.parseInt(string(monitor.config,"pollSeconds"));check(seconds>=10,"poll interval");
        do {
            String error=null;
            try {monitor.refresh();}catch(Exception e){error=e.toString();System.err.println(Instant.now()+" "+error);}
            if(monitor.publish(error)||once)break;
            Thread.sleep(seconds*1000L);
        }while(true);
    }
}
