/* Read-only compatibility authority for factual-residual-study-v1.
 * Executed by research audit in Java source-file mode with a selected retained
 * runtime, never with jars from the inspection checkout. No producer is invoked.
 * See README.md for the version-pinned checks not exported by the frozen API.
 */
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import kotlinx.serialization.KSerializer;
import kotlinx.serialization.builtins.BuiltinSerializersKt;
import kotlinx.serialization.json.*;
import org.mtgallium.evaluation.searchteacher.*;
import org.mtgallium.agent.searchteacher.SearchTeacherDeckManifest;
import org.mtgallium.agent.searchteacher.FactualOutcomeResidualEvaluatorKt;
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueEvaluatorKt;
import org.mtgallium.research.run.*;

class FactualResidualCompletionAudit {
    static final String MANIFEST = "research-run-manifest.json";
    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    static <T> T read(Path file, KSerializer<T> serializer) {
        return SearchTeacherSupportKt.readEvidenceJson(file, serializer);
    }
    static Path path(FactualResidualInput ref) {
        return Path.of(ref.getDirectory()).toAbsolutePath().normalize();
    }
    static Map<String, ResearchRunArtifact> artifacts(Path directory, String identity) {
        var manifest = ResearchRunArtifacts.Companion.loadAndVerify(directory, identity);
        var result = new LinkedHashMap<String, ResearchRunArtifact>();
        for (var item : manifest.getArtifacts()) {
            check(result.put(item.getRelativePath(), item) == null, "Duplicate registered artifact");
        }
        return result;
    }
    static void verify(FactualResidualInput ref) {
        FactualResidualStudyInputsKt.verifyFactualResidualInput(ref, Set.of("report.json"));
    }
    static void localReference(FactualResidualInput ref, Path location,
                               Map<String, ResearchRunArtifact> root, String prefix) {
        check(path(ref).equals(location), "Unexpected " + prefix + " location");
        check(root.containsKey(prefix + "/" + MANIFEST) && root.containsKey(prefix + "/report.json"),
              "Unregistered " + prefix + " reference");
        check(ref.getManifestSha256().equals(root.get(prefix + "/" + MANIFEST).getSha256()),
              "Root and " + prefix + " manifest bindings differ");
        verify(ref);
    }
    static Map<String, Object> reference(FactualResidualInput ref) {
        return Map.of("directory", ref.getDirectory(), "identity", ref.getIdentity(),
                      "manifestSha256", ref.getManifestSha256());
    }
    static JsonElement json(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof JsonElement element) return element;
        if (value instanceof Boolean b) return JsonElementKt.JsonPrimitive(b);
        if (value instanceof Number n) return JsonElementKt.JsonPrimitive(n);
        if (value instanceof Map<?, ?> map) {
            var items = new LinkedHashMap<String, JsonElement>();
            map.forEach((key, item) -> items.put(key.toString(), json(item)));
            return new JsonObject(items);
        }
        if (value instanceof Collection<?> list) return new JsonArray(list.stream().map(FactualResidualCompletionAudit::json).toList());
        return JsonElementKt.JsonPrimitive(value.toString());
    }
    static void validateChild(FactualIncumbentTrajectoryReport trajectory, FactualResidualGameAllocation game,
                              FactualResidualStudyReport study, LoadedFactualResidualInputs inputs,
                              SearchTeacherDeckManifest deck,
                              Map<String, Map<String, ResearchRunArtifact>> sourceArtifactCache) {
        var calibration = inputs.getParents().get(game.getSourceRunIdentity());
        var sourceRef = inputs.getSources().get(game.getSourceRunIdentity());
        check(calibration != null && sourceRef != null, "Child source is outside the authenticated allocation");
        var request = new FactualIncumbentTrajectoryRequest(sourceRef.getDirectory(), sourceRef.getIdentity(),
            sourceRef.getManifestSha256(), calibration.getSourceProvenance(), calibration.getPlan(),
            game.getGameId(), game.getViewer(), game.getSeedGroupId(), study.getPlan().getBuild());
        check(trajectory.getRequest().equals(request) && trajectory.getProducer().equals(study.getProducer()) &&
              trajectory.getRuntime().equals(study.getRuntime()), "Child request/build/producer/runtime differs from its study");
        if (trajectory.getSource() != null) {
            var source = trajectory.getSource();
            check(source.getLeg() == game.getLeg() && source.getSemanticDecisions() == game.getSemanticDecisions(),
                  "Child source coordinate or decision count differs");
            var pairs = calibration.getComparisons().stream().flatMap(c -> c.getPairs().stream())
                .filter(p -> p.getGames().stream().anyMatch(g -> g.getGameId().equals(game.getGameId()))).toList();
            check(pairs.size() == 1, "Child game has no unique source pair");
            var pair = pairs.get(0);
            var actual = pair.getGames().get(game.getLeg());
            check(actual.getGameId().equals(game.getGameId()) && source.getPairIndex() == pair.getPairIndex() &&
                  source.getGameSeed() == actual.getSeed() && source.getSemanticDecisions() == actual.getDecisions(),
                  "Child game seed, pair or decision count differs from source");
            check(calibration.getPolicies().stream().filter(p -> p.getDescriptor().getId().equals(actual.getP0PolicyId()))
                      .toList().equals(List.of(source.getP0Policy())) &&
                  calibration.getPolicies().stream().filter(p -> p.getDescriptor().getId().equals(actual.getP1PolicyId()))
                      .toList().equals(List.of(source.getP1Policy())), "Child source policies differ");
            var sourceArtifacts = sourceArtifactCache.computeIfAbsent(sourceRef.getIdentity(),
                ignored -> artifacts(path(sourceRef), sourceRef.getIdentity()));
            check(source.getReportSha256().equals(sourceArtifacts.get("report.json").getSha256()) &&
                  source.getReplaySha256().equals(sourceArtifacts.get(source.getReplayReference()).getSha256()) &&
                  source.getReplaySha256().equals(actual.getReplaySha256()) &&
                  source.getCheckpointSha256().equals(sourceArtifacts.get(source.getCheckpointReference()).getSha256()),
                  "Child replay or checkpoint binding differs");
            var checkpoint = ResearchRunCheckpoints.INSTANCE.load(
                ResearchRunFiles.INSTANCE.resolveBelow(path(sourceRef), source.getCheckpointReference()));
            check(source.getCheckpointPayloadSha256().equals(checkpoint.getPayloadSha256()), "Checkpoint payload binding differs");
            check(source.getDeckHash().equals(SearchTeacherSupportKt.deckHash(deck)) &&
                  source.getCardPoolHash().equals(SearchTeacherSupportKt.cardPoolHash(deck)), "Child source deck differs");
        }
        if (trajectory.getDisposition() == FactualIncumbentTrajectoryDisposition.ADMITTED) {
            check(trajectory.getSource() != null && trajectory.getRows().size() == game.getSemanticDecisions(),
                  "Admitted child lacks the complete allocated trajectory");
        } else check(trajectory.getRows().isEmpty(), "A refused child cannot supply outcome rows");
    }
    static void validateReadout(FactualResidualAllocation allocation, List<FactualResidualSearchRow> rows,
                                FactualResidualReadoutGate recordedGate) {
        // A producer can fail while constructing the gate and retain rows without
        // a gate. Their population must still pass the owning coordinate checks.
        if (!rows.isEmpty() || recordedGate != null) {
            var computed = FactualResidualRootReadoutKt.factualResidualReadoutGate(allocation, rows);
            check(recordedGate == null || computed.equals(recordedGate), "Readout gate differs from its owning computation");
        }
    }
    public static void main(String[] args) {
        check(args.length == 3, "Expected RUN DECK EXPECTED_IDENTITY");
        Path directory = Path.of(args[0]).toAbsolutePath().normalize();
        var root = artifacts(directory, args[2]);
        check(root.keySet().containsAll(Set.of("report.json", "plan.json", "bindings.json")), "Missing registered study inputs");
        var study = read(directory.resolve("report.json"), FactualResidualStudyReport.Companion.serializer());
        var plan = read(directory.resolve("plan.json"), FactualResidualStudyPlan.Companion.serializer());
        var deck = read(Path.of(args[1]), SearchTeacherDeckManifest.Companion.serializer());
        check(plan.equals(study.getPlan()), "Retained plan differs from report plan");
        check(study.getBindings().getIdentity().equals(args[2]), "Root identity differs");
        check(read(directory.resolve("bindings.json"), ResearchRunBindings.Companion.serializer()).equals(study.getBindings()),
              "Root binding sidecar differs");
        check(FactualResidualStudyKt.factualResidualStudyBindings(plan, study.getProducer(), study.getRuntime(), deck)
              .equals(study.getBindings()), "Study material bindings differ");
        ResearchBuildKt.verifyResearchBuild(plan.getBuild(), study.getProducer(), study.getRuntime());
        var inputs = FactualResidualStudyInputsKt.loadFactualResidualInputs(plan, deck);
        var expectedAllocation = FactualResidualStudyInputsKt.allocateFactualResidualStudy(plan, inputs, args[2]);
        var games = expectedAllocation.getGames();
        var stages = new LinkedHashMap<String, Object>();
        var result = new LinkedHashMap<String, Object>();
        result.put("researchRunIdentity", args[2]);
        result.put("disposition", study.getDisposition().name());
        result.put("failure", study.getFailure());
        result.put("producer", Map.of("sourceRevision", study.getProducer().getOuterCommit(),
            "argentumRevision", study.getProducer().getCheckedOutEngineCommit(), "buildIdentity", plan.getBuild().getIdentity()));
        result.put("stages", stages);
        stages.put("allocation", study.getAllocation() == null ? "NOT_SEALED" : "VERIFIED");
        if (study.getAllocation() != null) {
            localReference(study.getAllocation(), directory.resolve("allocation"), root, "allocation");
            var actual = read(path(study.getAllocation()).resolve("report.json"), FactualResidualAllocation.Companion.serializer());
            check(actual.equals(expectedAllocation) && actual.getBindings().getIdentity().equals(study.getAllocation().getIdentity()),
                  "Frozen allocation differs from the owning allocator");
        } else check(study.getCorpus() == null && study.getTraining() == null, "Downstream reference without allocation");

        FactualResidualContinuation continuation = plan.getAdmissionParent() == null ? null :
            FactualResidualContinuationKt.loadFactualResidualContinuation(plan.getAdmissionParent(), plan, expectedAllocation, deck, inputs);
        Map<Integer, FactualResidualCorpusEntry> reused = continuation == null ? Map.of() : continuation.getEntries();
        FactualResidualCorpusReport corpus = null;
        if (study.getCorpus() != null) {
            localReference(study.getCorpus(), directory.resolve("corpus"), root, "corpus");
            corpus = read(path(study.getCorpus()).resolve("report.json"), FactualResidualCorpusReport.Companion.serializer());
            check(corpus.getAllocation().equals(study.getAllocation()), "Corpus allocation reference differs");
            check(corpus.getEntries().size() == games.size(), "Corpus omits allocated coordinates");
            // Version-pinned corpus-v1 formula; the frozen producer has no exported
            // corpus-binding function. All serialization and hashing use its APIs.
            var expected = new ResearchRunBindings(1, "factual-residual-corpus-v1", Map.of(
                "study", args[2], "allocation", study.getAllocation().getIdentity(),
                "allocation-manifest", study.getAllocation().getManifestSha256(), "entries", SearchTeacherSupportKt.sha256(
                    SearchTeacherSupportKt.getEvidenceJson().encodeToString(
                        BuiltinSerializersKt.ListSerializer(FactualResidualCorpusEntry.Companion.serializer()), corpus.getEntries()))));
            check(corpus.getBindings().equals(expected) && corpus.getBindings().getIdentity().equals(study.getCorpus().getIdentity()),
                  "Corpus material bindings differ");
        }
        stages.put("corpus", corpus == null ? "NOT_SEALED" : corpus.getComplete() ? "COMPLETE_VERIFIED" : "INCOMPLETE_VERIFIED");
        var childPattern = Pattern.compile("corpus/trajectories/game-(0|[1-9][0-9]*)/(report\\.json|research-run-manifest\\.json)");
        for (var name : root.keySet()) if (name.startsWith("corpus/trajectories/")) {
            var match = childPattern.matcher(name);
            check(match.matches() && Integer.parseInt(match.group(1)) < games.size(), "Unexpected registered trajectory coordinate");
        }
        var admitted = new HashSet<Integer>();
        var missing = new ArrayList<Integer>();
        var partial = new ArrayList<Integer>();
        var refs = new ArrayList<Map<String, Object>>();
        var roleRows = new LinkedHashMap<String, Integer>();
        int refused = 0, rows = 0, reusedCount = 0;
        var sourceArtifactCache = new HashMap<String, Map<String, ResearchRunArtifact>>();
        for (int i = 0; i < games.size(); i++) {
            var game = games.get(i);
            var entry = corpus == null ? null : corpus.getEntries().get(i);
            if (entry != null) check(entry.getAllocation().equals(game), "Corpus coordinate order differs");
            var prior = reused.get(i);
            FactualResidualInput ref = entry == null ? (prior == null ? null : prior.getTrajectory()) : entry.getTrajectory();
            String prefix = "corpus/trajectories/game-" + i;
            if (prior != null) {
                check(entry == null || entry.equals(prior), "Reused entry or parent identity changed");
                check(!root.containsKey(prefix + "/report.json") && !root.containsKey(prefix + "/" + MANIFEST),
                      "A reused coordinate also has a newly emitted trajectory");
                reusedCount++;
            } else {
                check(entry == null || entry.getReusedFromStudyIdentity() == null, "Unrecognized reuse origin");
                boolean hasReport = root.containsKey(prefix + "/report.json"), hasManifest = root.containsKey(prefix + "/" + MANIFEST);
                if (!hasReport || !hasManifest) {
                    check(ref == null, "Referenced trajectory lacks registered report/manifest pair");
                    (hasReport || hasManifest ? partial : missing).add(i);
                    continue;
                }
                var childManifest = read(directory.resolve(prefix).resolve(MANIFEST), ResearchRunArtifactManifest.Companion.serializer());
                var found = new FactualResidualInput(directory.resolve(prefix).toString(), childManifest.getResearchRunIdentity(),
                    root.get(prefix + "/" + MANIFEST).getSha256());
                check(ref == null || ref.equals(found), "Child reference differs from registered trajectory");
                ref = found;
                localReference(ref, directory.resolve(prefix), root, prefix);
            }
            // The continuation authority already decoded and checked reused
            // trajectories. Reuse its compact entries instead of reading them twice.
            FactualIncumbentTrajectoryDisposition disposition;
            int childRows;
            var origin = prior == null ? study : continuation.getParent();
            if (prior != null) {
                disposition = prior.getDisposition(); childRows = prior.getRows();
            } else {
                var trajectory = FactualIncumbentTrajectoryKt.loadVerifiedFactualIncumbentTrajectory(path(ref), ref.getIdentity());
                validateChild(trajectory, game, study, inputs, deck, sourceArtifactCache);
                disposition = trajectory.getDisposition(); childRows = trajectory.getRows().size();
                if (entry != null) check(entry.getDisposition() == disposition && entry.getRows() == childRows,
                                         "Corpus disposition or row count differs from retained child");
            }
            if (disposition == FactualIncumbentTrajectoryDisposition.ADMITTED) admitted.add(i); else refused++;
            rows += childRows;
            roleRows.merge(game.getRole().name(), childRows, Integer::sum);
            var item = new LinkedHashMap<String, Object>(reference(ref));
            item.put("coordinate", i); item.put("role", game.getRole().name()); item.put("rows", childRows);
            item.put("disposition", disposition.name()); item.put("producerStudyIdentity", origin.getBindings().getIdentity());
            item.put("sourceRevision", origin.getProducer().getOuterCommit());
            item.put("buildIdentity", origin.getPlan().getBuild().getIdentity()); item.put("reused", prior != null);
            refs.add(item);
        }
        var groupMembers = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < games.size(); i++) groupMembers.computeIfAbsent(games.get(i).getSeedGroupId(), ignored -> new ArrayList<>()).add(i);
        long completeGroups = groupMembers.values().stream().filter(indices -> indices.size() == 2 && admitted.containsAll(indices)).count();
        result.put("population", Map.of("plannedGames", games.size(), "plannedGroups", groupMembers.size(),
            "admittedGames", admitted.size(), "refusedGames", refused, "missingCoordinates", missing,
            "partialCoordinates", partial, "completeGroups", completeGroups, "rows", rows,
            "rowsByRole", roleRows, "reusedGames", reusedCount));
        result.put("trajectoryReferences", refs);
        stages.put("training", study.getTraining() == null ? "NOT_SEALED" : "CHECKPOINT_VERIFIED");
        if (study.getTraining() != null) {
            check(corpus != null && corpus.getComplete() && admitted.size() == games.size(), "Training without the full admitted corpus");
            localReference(study.getTraining(), directory.resolve("training"), root, "training");
            FactualResidualStudyKt.loadFactualResidualTraining(study.getTraining());
            var training = read(path(study.getTraining()).resolve("report.json"), FactualResidualTrainingReport.Companion.serializer());
            check(training.getCorpus().equals(study.getCorpus()) && training.getAllocation().equals(study.getAllocation()),
                  "Checkpoint training population differs");
            // The environment/training-v1 binding constructors are private in the
            // retained producer. Check their pinned formulas using frozen APIs.
            var environment = new ResearchRunBindings(1, "factual-residual-environment-v1", Map.of(
                "incumbent", SearchTeacherSupportKt.sha256(SearchTeacherSupportKt.getEvidenceJson().encodeToString(
                    SearchTeacherCalibrationPolicy.Companion.serializer(), plan.getIncumbent())),
                "source", SearchTeacherSupportKt.sha256(SearchTeacherSupportKt.getEvidenceJson().encodeToString(
                    ResearchRunProvenance.Companion.serializer(), study.getProducer())),
                "deployment", FactualOutcomeResidualEvaluatorKt.FACTUAL_OUTCOME_RESIDUAL_DEPLOYMENT,
                "anchor", FactualOutcomeResidualEvaluatorKt.FACTUAL_OUTCOME_RESIDUAL_ANCHOR));
            var binding = new ResearchRunBindings(1, "factual-residual-training-v1", Map.of(
                "corpus", study.getCorpus().getIdentity(), "corpus-manifest", study.getCorpus().getManifestSha256(),
                "allocation", study.getAllocation().getIdentity(), "allocation-manifest", study.getAllocation().getManifestSha256(),
                "environment", environment.getIdentity(), "target", FactualOutcomeResidualEvaluatorKt.FACTUAL_OUTCOME_RESIDUAL_TARGET,
                "feature-schema", LearnedOutcomeValueEvaluatorKt.LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1,
                "feature-scaling", LearnedOutcomeValueEvaluatorKt.LEARNED_OUTCOME_VALUE_FEATURE_SCALING_V1,
                "objective", FactualOutcomeResidualEvaluatorKt.FACTUAL_OUTCOME_RESIDUAL_OBJECTIVE,
                "weighting", FactualResidualStudyInputsKt.FACTUAL_RESIDUAL_WEIGHTING));
            check(training.getEnvironmentBindings().equals(environment) && training.getBindings().equals(binding),
                  "Training objective/environment material differs");
            int fittingFrames = games.stream().filter(g -> g.getRole() == FactualResidualDataRole.TRAIN)
                .mapToInt(g -> g.getFittingFrames().size()).sum();
            check(training.getFittingRows() == fittingFrames && Double.isFinite(training.getMaxGradientResidual()) &&
                  training.getMaxGradientResidual() >= 0 && training.getMaxGradientResidual() <= 1e-9,
                  "Training frame count or recorded numerical certificate differs");
        } else check(study.getPredictions() == null && study.getSearches().isEmpty() && study.getGate() == null,
                     "Downstream result without a sealed checkpoint");
        stages.put("predictions", study.getPredictions() == null ? "NOT_RECORDED" : "RECORDED");
        if (study.getPredictions() != null) {
            var prediction = study.getPredictions();
            var screen = games.stream().filter(g -> g.getRole() == FactualResidualDataRole.SCREEN).toList();
            check(prediction.getGames() == screen.size() && prediction.getSeedGroups() == screen.stream()
                .map(FactualResidualGameAllocation::getSeedGroupId).distinct().count() &&
                prediction.getFrames() == roleRows.getOrDefault("SCREEN", 0), "Prediction population differs from SCREEN");
            check(Double.isFinite(prediction.getAnchorMeanSquaredError()) && prediction.getAnchorMeanSquaredError() >= 0 &&
                Double.isFinite(prediction.getResidualMeanSquaredError()) && prediction.getResidualMeanSquaredError() >= 0 &&
                Double.isFinite(prediction.getWeightedClippingFraction()) && prediction.getWeightedClippingFraction() >= 0 &&
                prediction.getWeightedClippingFraction() <= 1, "Invalid recorded prediction metric");
        }
        stages.put("gate", study.getGate() == null ? "NOT_RECORDED" : "RECOMPUTED");
        validateReadout(expectedAllocation, study.getSearches(), study.getGate());
        var searches = new LinkedHashMap<String, Object>();
        int plannedSearches = (int) games.stream().filter(g -> g.getRole() == FactualResidualDataRole.SCREEN).count() * plan.getSearchRepetitions() * 2;
        searches.put("planned", plannedSearches); searches.put("recordedRows", study.getSearches().size());
        searches.put("completed", study.getSearches().stream().filter(r -> r.getDisposition() == FactualResidualReadoutDisposition.SEARCHED).count());
        searches.put("attempted", study.getPredictions() == null ? 0 : study.getSearches().isEmpty() ? null :
            study.getSearches().stream().filter(FactualResidualSearchRow::getSearchAttempted).count());
        searches.put("unreportedRows", plannedSearches - study.getSearches().size());
        result.put("searches", searches);
        result.put("fitAttempted", study.getTraining() == null ? null : true);
        boolean candidate = corpus == null && study.getAllocation() != null && study.getTraining() == null &&
            plan.getAdmissionParent() == null && !admitted.isEmpty() && admitted.size() < games.size() &&
            refused == 0 && partial.isEmpty() && Math.ceil(study.getElapsedSeconds()) < plan.getMaximumSeconds();
        result.put("recovery", Map.of("admissionContinuationCandidate", candidate, "automaticResume", false,
            "eligibility", "Requires an explicit proposed plan and the native continuation preflight; discovery does not authorize execution."));
        result.put("limits", List.of("No replay, label projection, fit, prediction evaluation or new search was executed.",
            "A missing checkpoint does not establish that fitting was never attempted.",
            "Prediction values are retained observations, not recomputed metrics or strength evidence."));
        System.out.println(json(result));
    }
}
