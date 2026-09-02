package org.mtgallium.agent.infoset.argentum

enum class ProjectionDisposition { PROJECTED, INTENTIONALLY_OMITTED, UNSUPPORTED }

/** Safe conformance metadata. The raw event object and engine identifiers never cross this API. */
data class PerspectiveProjectionAudit(
    val rawEventType: String,
    val viewerAlias: String,
    val disposition: ProjectionDisposition,
    val projectedKind: String? = null,
    val detailType: String? = null,
)

fun interface PerspectiveProjectionAuditSink {
    fun record(audit: PerspectiveProjectionAudit)

    companion object {
        val NONE = PerspectiveProjectionAuditSink { }
    }
}
