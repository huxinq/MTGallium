package org.mtgallium.evaluation.searchteacher

import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.Serializable
import org.mtgallium.research.run.ResearchRunFiles

/**
 * Operational-only progress payload accepted by tools/durable-run.
 *
 * This is deliberately distinct from [org.mtgallium.research.run.ResearchRunProgress]:
 * durable-run owns a small, versioned wire format and does not make research identity or
 * state part of its progress-file schema.
 */
@Serializable
internal data class DurableRunProgressUpdate(
    val schemaVersion: Int = 1,
    val updatedAt: String = Instant.now().toString(),
    val completed: Long,
    val total: Long,
    val unit: String = "pairs",
    val phase: String,
    val detail: String? = null,
) {
    init {
        require(schemaVersion == 1)
        require(completed >= 0)
        require(total > 0 && completed <= total)
        require(unit.isNotBlank())
        require(phase.isNotBlank())
    }
}

internal fun publishDurableRunProgress(
    path: Path?,
    completed: Int,
    total: Int,
    phase: String,
    detail: String,
) {
    val target = path ?: return
    ResearchRunFiles.atomicWrite(
        target,
        evidenceJson.encodeToString(
            DurableRunProgressUpdate(
                completed = completed.toLong(),
                total = total.toLong(),
                phase = phase,
                detail = detail,
            ),
        ) + "\n",
    )
}
