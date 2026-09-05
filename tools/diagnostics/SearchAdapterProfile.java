import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import jdk.jfr.*;
import kotlinx.serialization.json.Json;
import org.mtgallium.agent.infoset.argentum.*;
import org.mtgallium.agent.infoset.core.*;
import org.mtgallium.agent.searchteacher.*;
import org.mtgallium.evaluation.searchteacher.*;
import org.mtgallium.research.run.*;

/** Fresh-root cost diagnostic; its synthetic positions are neither a strength panel nor games. */
public final class SearchAdapterProfile {
    @Name("mtgallium.AdapterProfileStage")
    @Label("MTGallium adapter diagnostic stage")
    @StackTrace(false)
    public static final class Stage extends Event {
        public String caseId;
        public String stage;
        public int repetition;
    }

    private static final com.sun.management.ThreadMXBean THREAD =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final List<String> CASES = List.of("lethal-06", "attack-05", "block-03", "hidden-2-1");

    private record Cost(long wallNanos, long cpuNanos, long allocatedBytes) {
        String json() {
            return "{\"wallNanos\":" + wallNanos + ",\"cpuNanos\":" + cpuNanos
                + ",\"allocatedBytes\":" + allocatedBytes + "}";
        }
    }
    private record Measured<T>(T value, Cost cost) {}

    private static <T> Measured<T> measure(String caseId, String stage, int repetition,
                                          java.util.function.Supplier<T> operation) {
        var event = new Stage();
        event.caseId = caseId;
        event.stage = stage;
        event.repetition = repetition;
        long thread = Thread.currentThread().threadId();
        event.begin();
        long bytes = THREAD.getThreadAllocatedBytes(thread);
        long cpu = THREAD.getCurrentThreadCpuTime();
        long wall = System.nanoTime();
        T value = operation.get();
        long elapsed = System.nanoTime() - wall;
        long cpuElapsed = THREAD.getCurrentThreadCpuTime() - cpu;
        long allocated = THREAD.getThreadAllocatedBytes(thread) - bytes;
        event.end();
        event.commit();
        return new Measured<>(value, new Cost(elapsed, cpuElapsed, allocated));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("repository-root output-relative-path measured-repetitions");
        Path repository = Path.of(args[0]).toAbsolutePath();
        Path output = PrivateEvidencePaths.INSTANCE.resolve(repository, args[1]);
        int repetitions = Integer.parseInt(args[2]);
        if (repetitions < 1 || repetitions > 10) throw new IllegalArgumentException("Use 1..10 repetitions");
        Files.createDirectories(output);
        if (Files.exists(output.resolve("results.jsonl"))) throw new IllegalArgumentException("Output already used");
        var provenance = ResearchRunProvenance.Companion.capture(repository, "third_party/argentum-engine");
        provenance.requireReady();
        if (provenance.getOuterDirty() || provenance.getEngineDirty()) throw new IllegalStateException("Commit source before profiling");
        if (ManagementFactory.getRuntimeMXBean().getInputArguments().stream().anyMatch(a -> a.startsWith("-javaagent"))) {
            throw new IllegalStateException("Profile without instrumentation agents");
        }
        THREAD.setThreadCpuTimeEnabled(true);
        THREAD.setThreadAllocatedMemoryEnabled(true);
        Files.writeString(output.resolve("provenance.json"), Json.Default.encodeToString(
            ResearchRunProvenance.Companion.serializer(), provenance));
        var cards = new LinkedHashMap<String, Integer>();
        cards.put("Mountain", 24);
        for (String card : List.of("Shock", "Lightning Strike", "Hired Claw", "Hexing Squelcher",
                "Burnout Bashtronaut", "Magebane Lizard", "Razorkin Needlehead", "Nova Hellkite", "Sunspine Lynx")) {
            cards.put(card, 4);
        }
        var deck = new SearchTeacherDeckManifest("adapter-profile-synthetic-v1", "Adapter profile fixture",
            "synthetic", "2026-09-05", "public tactical fixtures", cards, Map.of());
        Files.writeString(output.resolve("deck.json"), Json.Default.encodeToString(SearchTeacherDeckManifest.Companion.serializer(), deck));
        var registry = SearchTeacherSupportKt.buildRegistry();
        var config = new SearchTeacherRuntimeConfig();
        var factory = new TacticalScenarioFactory(registry, deck, config.getActionSpaceProfile());
        var knownDecks = Map.of("p0", deck.getMainDeck(), "p1", deck.getMainDeck());
        var cases = TacticalBenchmarkCatalog.INSTANCE.getCases().stream().filter(c -> CASES.contains(c.getId())).toList();
        if (cases.size() != CASES.size()) throw new IllegalStateException("Missing diagnostic cases");
        try (var rows = Files.newBufferedWriter(output.resolve("results.jsonl"), StandardOpenOption.CREATE_NEW);
             var recording = new Recording(Configuration.getConfiguration("profile"))) {
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(2));
            recording.enable("jdk.ObjectAllocationSample").with("throttle", "1000/s");
            recording.enable("mtgallium.AdapterProfileStage").withThreshold(Duration.ZERO);
            for (int repetition = -1; repetition < repetitions; repetition++) {
                if (repetition == 0) recording.start();
                for (var fixture : cases) {
                    var world = factory.create(fixture); // Fixture construction is outside measured stages.
                    String actor = Objects.requireNonNull(world.actorToAct());
                    var construction = measure(fixture.getId(), "construction", repetition, () ->
                        new SearchTeacherPolicySession(world, actor, knownDecks, config.policyParameters(),
                            SearchTeacherOpponentPoliciesKt.defaultMonoRedOpponentPolicy(
                                OpponentPolicyReplacementEvidenceDisposition.INVALIDATES_EVIDENCE), fixture.getId(),
                            SearchTeacherSearchFactory.INSTANCE.rootRolloutPolicy(),
                            SearchTeacherSearchFactory.INSTANCE.opponentRolloutPolicy(), null,
                            new SearchTeacherIntegrationSpecification(), ArgentumBeliefProposalAuditSink.Companion.getNONE()));
                    var session = construction.value();
                    if (repetition == -1) Files.writeString(output.resolve(fixture.getId() + "-behavior.json"),
                        Json.Default.encodeToString(SearchTeacherBehaviorSpecification.Companion.serializer(), session.getBehaviorSpecification()));
                    var selection = measure(fixture.getId(), "selection", repetition,
                        () -> session.select(world, actor, fixture.getRootSeed() + 700000L));
                    var result = Objects.requireNonNull(selection.value().getSearch(), "Fixture must invoke search");
                    if (result.getDiagnostics().getSimulations() != 64 || result.getDiagnostics().getRejectedTransitions() != 0) {
                        throw new IllegalStateException("Fixed work failed");
                    }
                    rows.write("{\"caseId\":\"" + fixture.getId() + "\",\"repetition\":" + repetition
                        + ",\"construction\":" + construction.cost().json() + ",\"selection\":" + selection.cost().json()
                        + ",\"belief\":" + Json.Default.encodeToString(BeliefDiagnostics.Companion.serializer(), session.getLatestBeliefDiagnostics())
                        + ",\"result\":" + Json.Default.encodeToString(InformationSetSearchResult.Companion.serializer(), result) + "}\n");
                    rows.flush();
                    System.out.println(fixture.getId() + " repetition=" + repetition + " construction="
                        + construction.cost().wallNanos() / 1e6 + "ms selection=" + selection.cost().wallNanos() / 1e6 + "ms");
                }
            }
            recording.stop();
            recording.dump(output.resolve("profile.jfr"));
        }
        Files.copy(repository.resolve("tools/diagnostics/SearchAdapterProfile.java"), output.resolve("SearchAdapterProfile.java"));
        Files.copy(repository.resolve("tools/diagnostics/search-adapter-profile.init.gradle"), output.resolve("classpath.init.gradle"));
        var after = ResearchRunProvenance.Companion.capture(repository, "third_party/argentum-engine");
        if (!after.equals(provenance)) throw new IllegalStateException("Source changed during profiling");
        Files.writeString(output.resolve("runtime-configuration.txt"), System.getProperty("java.runtime.version") + "\n"
            + ManagementFactory.getRuntimeMXBean().getInputArguments() + "\n");
        Files.writeString(output.resolve("runtime.txt"), System.getProperty("java.runtime.version") + "\n"
            + ManagementFactory.getRuntimeMXBean().getInputArguments() + "\n"
            + "availableProcessors=" + Runtime.getRuntime().availableProcessors() + "\n"
            + "systemLoadAverage=" + ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage() + "\n"
            + "javaProcesses=" + ProcessHandle.allProcesses().filter(p -> p.info().command().orElse("").endsWith("/java")).map(ProcessHandle::pid).toList() + "\n"
            + "classpath=" + System.getProperty("java.class.path") + "\n");
        var material = new LinkedHashMap<String, String>();
        for (String name : List.of("provenance.json", "deck.json", "SearchAdapterProfile.java", "classpath.init.gradle", "runtime-configuration.txt")) {
            material.put(name.toLowerCase().replace('.', '-'), ResearchRunKt.researchSha256File(output.resolve(name)));
        }
        for (String caseId : CASES) material.put(caseId, ResearchRunKt.researchSha256File(output.resolve(caseId + "-behavior.json")));
        material.put("measured-repetitions", Integer.toString(repetitions));
        var bindings = new ResearchRunBindings(1, "mtgallium-search-adapter-profile-v1", material);
        Files.writeString(output.resolve("bindings.json"), Json.Default.encodeToString(ResearchRunBindings.Companion.serializer(), bindings));
        Files.writeString(output.resolve("measurement-summary.txt"), "All calls returned for " + repetitions
            + " measured repetitions of four fresh roots; one warmup per root. Only the finalized artifact manifest establishes completion.\n");
        var artifacts = new ResearchRunArtifacts(output, bindings.getIdentity());
        for (String name : List.of("provenance.json", "deck.json", "SearchAdapterProfile.java", "classpath.init.gradle",
                "runtime-configuration.txt", "runtime.txt", "bindings.json", "results.jsonl", "profile.jfr", "measurement-summary.txt")) artifacts.register(name);
        for (String caseId : CASES) artifacts.register(caseId + "-behavior.json");
        artifacts.finalize();
        var verified = ResearchRunArtifacts.Companion.loadAndVerify(output, bindings.getIdentity());
        System.out.println("Verified " + verified.getResearchRunIdentity() + " artifacts=" + verified.getArtifacts().size());
    }
}
