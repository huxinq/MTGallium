package org.mtgallium.evaluation.searchteacher.cli

import java.nio.file.Path
import org.mtgallium.evaluation.searchteacher.cli.commands.*

/** Preparation belongs to the command contract, not its position in a dispatcher. */
internal enum class CommandPreparation {
    /** The handler owns its input/source checks, including read-only historical inspections. */
    HANDLER,
    CURRENT_SOURCE,
    /** Older arena commands also load the frozen profile and print their common run header. */
    LEGACY_ARENA,
}

internal class SearchTeacherCommand(
    val id: String,
    val preparation: CommandPreparation,
    private val handler: SearchTeacherCommandContext.() -> Unit,
) {
    fun run(root: Path, options: SearchTeacherCli) {
        val context = SearchTeacherCommandContext(root, options)
        when (preparation) {
            CommandPreparation.HANDLER -> Unit
            CommandPreparation.CURRENT_SOURCE -> context.requireCurrentSource()
            CommandPreparation.LEGACY_ARENA -> {
                context.requireCurrentSource()
                context.prepareArena()
            }
        }
        context.handler()
    }
}

/** The catalog and dispatcher share these registrations: every advertised suite is executable. */
internal object SearchTeacherSuites {
    private val definitions = listOf(
        retainedInspectionCommands,
        researchWorkflowCommands,
        decisionLocalCommands,
        outcomeLearningCommands,
        behavioralCloningCommands,
        replayDiagnosticCommands,
        tacticalCommands,
        tournamentCommands,
        arenaCommands,
    ).flatten().let { commands ->
        require(commands.map { it.id }.distinct().size == commands.size) { "Duplicate Search Teacher command" }
        commands.associateBy { it.id }.toSortedMap()
    }

    fun require(id: String): SearchTeacherCommand =
        requireNotNull(definitions[id]) { "Unknown suite $id" }

    fun all(): List<SearchTeacherCommand> = definitions.values.toList()
}
