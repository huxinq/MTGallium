package org.mtgallium.evaluation.searchteacher

import kotlinx.serialization.Serializable

/**
 * Read-only schemas for real historical tournament manifests. Their field names describe the old
 * artifact layout and confer no lifecycle or approval meaning on current experiments.
 */
@Serializable
internal data class TournamentV3CalibratedManifest(
    val schemaVersion: Int = 1,
    val runIdentity: String,
    val outerCommit: String,
    val argentumCommit: String,
    val reviewManifestSha256: String,
    val workReportPath: String,
    val workReportSha256: String,
    val workMarkdownPath: String,
    val workMarkdownSha256: String,
    val promotedReportPath: String,
    val promotedReportSha256: String,
    val promotedMarkdownPath: String,
    val promotedMarkdownSha256: String,
    val workAnalyticsPath: String,
    val workAnalyticsSha256: String,
    val promotedAnalyticsPath: String,
    val promotedAnalyticsSha256: String,
    val artifacts: List<TournamentArtifactDigest>,
    val artifactsSha256: String,
)

@Serializable
internal data class TournamentAmendmentManifest(
    val schemaVersion: Int = 2,
    val amendmentIdentity: String,
    val sourceRunIdentity: String,
    val sourceReportSha256: String,
    val sourceInventorySha256: String,
    val repairOuterCommit: String,
    val repairArgentumCommit: String,
    val repairImplementationSha256: String,
    val replacementGameIds: List<String>,
    val reportPath: String,
    val reportSha256: String,
    val markdownPath: String,
    val markdownSha256: String,
    val promotedReportPath: String,
    val promotedReportSha256: String,
    val promotedMarkdownPath: String,
    val promotedMarkdownSha256: String,
    val amendmentArtifacts: List<TournamentArtifactDigest>,
    val amendmentArtifactsSha256: String,
)
