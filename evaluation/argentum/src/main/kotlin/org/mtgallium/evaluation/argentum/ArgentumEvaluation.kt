package org.mtgallium.evaluation.argentum

import com.wingedsheep.gym.contract.SchemaHash
import java.nio.file.Path
import java.time.Instant

fun main(args: Array<String>) {
    val options = CliOptions.parse(args)
    val root = Path.of("").toAbsolutePath().normalize()
    val manifest = loadDeckManifest()
    val registry = buildRegistry()
    val context = EvaluationContext(root, registry, manifest, options.seed)

    println("Argentum evaluation: loading ${registry.size} registered cards")
    val probes = runStaticAndContractProbes(context)
    probes.forEach { println("[${it.status}] ${it.id}: ${it.summary}") }

    val games = if (options.suite == "smoke") minOf(options.games, 2) else options.games
    val mulliganGames = if (options.suite == "smoke") 0 else options.mulliganGames
    val corpora = listOf(
        runReliabilityCorpus(context, "diagnostic-mirror", diagnosticDeck(), games, skipMulligans = true),
        runReliabilityCorpus(context, "mono-red-mirror", manifest.mainDeck(), games, skipMulligans = true),
        runReliabilityCorpus(context, "mono-red-mulligans", manifest.mainDeck(), mulliganGames, skipMulligans = false),
    )
    corpora.forEach {
        println("[CORPUS] ${it.id}: ${it.completedGames}/${it.requestedGames} complete, " +
            "${it.rejectedGames} rejected, ${it.truncatedGames} truncated, ${it.exceptions} exceptions")
    }

    val metrics = runBenchmarks(context, smoke = options.suite == "smoke")
    val (decisions, overall) = decide(probes, corpora)
    val report = EvaluationReport(
        metadata = EvaluationMetadata(
            generatedAt = Instant.now().toString(),
            argentumCommit = gitOutput(root.resolve("third_party/argentum-engine"), "rev-parse", "HEAD"),
            gymSchemaHash = SchemaHash.CURRENT,
            javaVersion = System.getProperty("java.version"),
            os = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
            suite = options.suite,
            baseSeed = options.seed,
            deckId = manifest.id,
            deckHash = manifest.deckHash(),
        ),
        probes = probes,
        corpora = corpora,
        metrics = metrics,
        decisions = decisions,
        overallVerdict = overall,
    )
    writeReport(root, report)
    println("Overall verdict: $overall")
    println("Reports: ${root.resolve("reports/argentum/latest")}")
}

private data class CliOptions(
    val suite: String = "full",
    val seed: Long = 20260822L,
    val games: Int = 100,
    val mulliganGames: Int = 20,
) {
    companion object {
        fun parse(args: Array<String>): CliOptions {
            var suite = "full"
            var seed = 20260822L
            var games = 100
            var mulliganGames = 20
            var index = 0
            while (index < args.size) {
                when (val arg = args[index]) {
                    "--suite" -> suite = args.requireValue(++index, arg)
                    "--seed" -> seed = args.requireValue(++index, arg).toLong()
                    "--games" -> games = args.requireValue(++index, arg).toInt()
                    "--mulligan-games" -> mulliganGames = args.requireValue(++index, arg).toInt()
                    else -> error("Unknown argument: $arg")
                }
                index++
            }
            require(suite in setOf("full", "smoke")) { "--suite must be full or smoke" }
            require(games >= 0) { "--games must be non-negative" }
            require(mulliganGames >= 0) { "--mulligan-games must be non-negative" }
            return CliOptions(suite, seed, games, mulliganGames)
        }

        private fun Array<String>.requireValue(index: Int, option: String): String =
            getOrNull(index) ?: error("$option requires a value")
    }
}
