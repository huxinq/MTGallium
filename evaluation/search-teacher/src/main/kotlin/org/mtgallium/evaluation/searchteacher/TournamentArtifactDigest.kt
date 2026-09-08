package org.mtgallium.evaluation.searchteacher

import kotlinx.serialization.Serializable

/** Retained artifact metadata shared by evaluator checkpoints and historical manifest decoding. */
@Serializable
internal data class TournamentArtifactDigest(
    val path: String,
    val sha256: String,
    val bytes: Long,
)
