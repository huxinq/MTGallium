import java.io.*;
import java.nio.file.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.zip.GZIPInputStream;
import kotlinx.serialization.json.*;
import org.mtgallium.agent.infoset.core.*;
import org.mtgallium.agent.infoset.argentum.*;
import org.mtgallium.research.run.*;

/** Bounded byte-parity/allocation screen over manifest-verified retained p0 observations. No games. */
public final class ObservationFragmentBenchmark {
    private static volatile String consumed;
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("input-directory expected-input-identity repository-root output-relative-path");
        Path input = Path.of(args[0]), repository = Path.of(args[2]).toAbsolutePath();
        Path output = PrivateEvidencePaths.INSTANCE.resolve(repository, args[3]);
        if (Files.exists(output)) throw new IllegalArgumentException("Output already exists");
        var provenance = ResearchRunProvenance.Companion.capture(repository, "third_party/argentum-engine");
        provenance.requireReady();
        if (provenance.getOuterDirty() || provenance.getEngineDirty()) throw new IllegalStateException("Commit source before measurement");
        var verified = ResearchRunArtifacts.Companion.loadAndVerify(input, args[1]);
        var material = new TreeMap<String, String>();
        material.put("source", provenance.getOuterCommit());
        material.put("argentum", provenance.getCheckedOutEngineCommit());
        material.put("input", verified.getResearchRunIdentity());
        material.put("input-manifest", ResearchRunKt.researchSha256File(input.resolve("research-run-manifest.json")));
        material.put("configuration", "all-registered-p0-streams;2-warmups;6-alternating-rounds;5-passes;empty-cache-per-stream;current-thread-allocation");
        material.put("jvm", System.getProperty("java.runtime.version") + ";" + ManagementFactory.getRuntimeMXBean().getInputArguments());
        String runtime = captureRuntime();
        material.put("runtime-sha256", ResearchRunKt.researchSha256(runtime));
        var bindings = new ResearchRunBindings(1, "observation-fragment-benchmark-v1", material);
        Files.createDirectories(output);
        Files.writeString(output.resolve("provenance.json"), Json.Default.encodeToString(ResearchRunProvenance.Companion.serializer(), provenance));
        Files.writeString(output.resolve("bindings.json"), Json.Default.encodeToString(ResearchRunBindings.Companion.serializer(), bindings));
        Files.writeString(output.resolve("runtime.txt"), runtime);
        try (var report = new PrintStream(Files.newOutputStream(output.resolve("report.md")), false, java.nio.charset.StandardCharsets.UTF_8)) {
            benchmark(input, verified, report, bindings.getIdentity(), provenance.getOuterCommit());
            if (report.checkError()) throw new IOException("Could not write benchmark report");
        }
        if (!verified.equals(ResearchRunArtifacts.Companion.loadAndVerify(input, args[1]))) throw new IllegalStateException("Input changed during measurement");
        if (!runtime.equals(captureRuntime())) throw new IllegalStateException("Runtime changed during measurement");
        if (!provenance.equals(ResearchRunProvenance.Companion.capture(repository, "third_party/argentum-engine"))) throw new IllegalStateException("Source changed during measurement");
        var artifacts = new ResearchRunArtifacts(output, bindings.getIdentity());
        for (String name : List.of("provenance.json", "bindings.json", "runtime.txt", "report.md")) artifacts.register(name);
        artifacts.finalize();
        ResearchRunArtifacts.Companion.loadAndVerify(output, bindings.getIdentity());
        System.out.println("Verified " + bindings.getIdentity() + "; read " + output.resolve("report.md"));
    }
    private static String captureRuntime() throws Exception {
        var runtime = new StringBuilder();
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            Path path = Path.of(entry).toAbsolutePath();
            runtime.append("classpath=").append(path).append('\n');
            if (Files.isDirectory(path)) {
                try (var files = Files.walk(path)) {
                    for (Path file : files.filter(Files::isRegularFile).sorted().toList()) runtime.append(file).append(' ').append(ResearchRunKt.researchSha256File(file)).append('\n');
                }
            } else runtime.append(ResearchRunKt.researchSha256File(path)).append('\n');
        }
        return runtime.toString();
    }
    private static void benchmark(Path input, ResearchRunArtifactManifest verified, PrintStream report, String identity, String source) throws Exception {
        var paths = verified.getArtifacts().stream().map(a -> a.getRelativePath())
            .filter(p -> p.startsWith("public/") && p.endsWith(".p0.jsonl.gz")).sorted().toList();
        if (paths.isEmpty()) throw new IllegalStateException("No registered p0 observation streams");
        var games = new ArrayList<List<PolicyObservation>>();
        long reused = 0, encoded = 0, observations = 0;
        for (String name : paths) {
            var views = new ArrayList<PolicyObservation>();
            try (var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(input.resolve(name))), java.nio.charset.StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    var row = (JsonObject) Json.Default.parseToJsonElement(line);
                    if (!((JsonPrimitive)row.get("type")).getContent().equals("decision")) continue;
                    var observation = ((JsonObject) row.get("policyInput")).get("observation");
                    views.add(PolicyJson.INSTANCE.getFormat().decodeFromString(PolicyObservation.Companion.serializer(), observation.toString()));
                }
            }
            ObservationCanonicalFragments previous = null;
            for (var view : views) {
                var fragments = ObservationCanonicalFragments.Companion.build(view, previous);
                String canonical = canonical(view);
                if (!Arrays.equals(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8), fragments.canonicalBytes())
                        || !PolicyJson.INSTANCE.sha256(canonical).equals(fragments.digest())
                        || !view.getObservationDigest().equals(fragments.digest())) throw new IllegalStateException("Observation byte/digest mismatch");
                reused += fragments.getReusedCards(); encoded += fragments.getEncodedCards(); observations++;
                previous = fragments;
            }
            games.add(views);
        }
        var bean = ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation measurement unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        report.println("# Canonical observation fragment screen\n");
        report.println("Run `" + identity + "`; analysis source `" + source + "`. Source, Argentum, actual runtime and configuration are bound by this output manifest.\n");
        report.println("Verified input `" + verified.getResearchRunIdentity() + "`; " + games.size() + " p0 streams / " + observations + " observations. All canonical bytes and retained digests match.\n");
        report.println("Reuse totals (including each stream's cold first view): " + reused + " reused / " + encoded + " encoded card fragments.\n");
        report.println("No projection, reference refinement, knowledge construction, gameplay or strength is measured. Inputs are successive retained p0 decisions, not rollout-step views. Allocations are current-thread bytes; elapsed timing is host-load sensitive. Two unmeasured warmups per method; six alternating rounds, five passes each.\n");
        for (int i=0; i<2; i++) { measure(games, false, bean); measure(games, true, bean); }
        report.println("| Round | Method | Milliseconds | Allocated bytes |\n| ---: | --- | ---: | ---: |");
        for (int round=0; round<6; round++) {
            for (int side=0; side<2; side++) {
                boolean fragments = (round + side) % 2 == 1;
                long[] cost = measure(games, fragments, bean);
                report.printf(Locale.ROOT, "| %d | %s | %.3f | %d |%n", round, fragments ? "fragments" : "full serializer", cost[0]/1e6, cost[1]);
            }
        }
    }
    private static String canonical(PolicyObservation view) {
        return ObservationCanonicalFragments.Companion.canonicalOracle(view);
    }
    private static long[] measure(List<List<PolicyObservation>> games, boolean fragments, com.sun.management.ThreadMXBean bean) {
        long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()), start = System.nanoTime();
        for (int pass=0; pass<5; pass++) for (var game : games) {
            ObservationCanonicalFragments previous = null;
            for (var view : game) {
                if (fragments) { previous = ObservationCanonicalFragments.Companion.build(view, previous); consumed = previous.digest(); }
                else consumed = PolicyJson.INSTANCE.sha256(canonical(view));
            }
        }
        return new long[] {System.nanoTime()-start, bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocated};
    }
}
