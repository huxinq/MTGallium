import java.nio.file.*;
import java.util.*;
import kotlinx.serialization.json.Json;
import org.mtgallium.research.run.*;

/** Seals prepared, private factory-comparison inputs through the existing artifact authority. */
public class FactoryComparisonBundle {
    static void verifyRuntimeFiles(Path root) throws Exception {
        String[] expected = Files.readString(root.resolve("classpath.txt")).strip().split(":");
        String[] actual = System.getProperty("java.class.path").split(":");
        if (expected.length != actual.length) throw new IllegalStateException("Runtime classpath length differs");
        for (int i = 0; i < expected.length; i++) {
            if (!Path.of(expected[i]).toRealPath().equals(Path.of(actual[i]).toRealPath())) {
                throw new IllegalStateException("Runtime classpath differs at " + i);
            }
        }
        for (String line : Files.readAllLines(root.resolve("runtime-files.tsv"))) {
            String[] parts = line.split("\\t", 2);
            if (parts.length != 2 || !ResearchRunKt.researchSha256File(Path.of(parts[1])).equals(parts[0])) {
                throw new IllegalStateException("Runtime input changed: " + line);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        var material = new LinkedHashMap<String, String>();
        // The caller supplies exact committed revisions and the effective adapter source/patch.
        // Binary hashes bind execution inputs; they are not a claim of reproducible compilation.
        for (String name : List.of("source-revisions.txt", "engine.patch", "adapter.patch",
                "ArgentumSearchWorld.kt", "build-command.txt", "ai.jar", "adapter.jar", "harness.jar")) {
            material.put(name.toLowerCase().replace('.', '-'), ResearchRunKt.researchSha256File(root.resolve(name)));
        }
        var inventory = new StringBuilder();
        for (String entry : Files.readString(root.resolve("classpath.txt")).strip().split(":")) {
            Path path = Path.of(entry);
            if (Files.isDirectory(path)) {
                try (var files = Files.walk(path)) {
                    for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                        inventory.append(ResearchRunKt.researchSha256File(file)).append('\t').append(file).append('\n');
                    }
                }
            } else inventory.append(ResearchRunKt.researchSha256File(path)).append('\t').append(path).append('\n');
        }
        Files.writeString(root.resolve("runtime-files.tsv"), inventory);
        material.put("runtime-files", ResearchRunKt.researchSha256File(root.resolve("runtime-files.tsv")));
        var bindings = new ResearchRunBindings(1, "mtgallium-ai-factory-runtime-bundle-v1", material);
        Files.writeString(root.resolve("bindings.json"), Json.Default.encodeToString(ResearchRunBindings.Companion.serializer(), bindings));
        var artifacts = new ResearchRunArtifacts(root, bindings.getIdentity());
        try (var files = Files.list(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) artifacts.register(file.getFileName().toString());
        }
        artifacts.finalize();
        ResearchRunArtifacts.Companion.loadAndVerify(root, bindings.getIdentity());
        System.out.println(bindings.getIdentity());
    }
}
