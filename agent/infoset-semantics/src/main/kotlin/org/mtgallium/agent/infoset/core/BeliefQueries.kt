package org.mtgallium.agent.infoset.core

/** Opaque runtime identity, not a serialization key or hash of hidden hypotheses. */
class BeliefSnapshotToken internal constructor()

class BeliefSnapshotBinding private constructor(
    val perspectivePlayerId: String,
    val epistemicDigest: String,
    val inferenceModelIdentity: String,
    val snapshotToken: BeliefSnapshotToken,
) {
    companion object {
        fun create(perspectivePlayerId: String, epistemicDigest: String, inferenceModelIdentity: String): BeliefSnapshotBinding {
            require(perspectivePlayerId.isNotBlank() && epistemicDigest.isNotBlank() && inferenceModelIdentity.isNotBlank())
            return BeliefSnapshotBinding(perspectivePlayerId, epistemicDigest, inferenceModelIdentity, BeliefSnapshotToken())
        }
    }
}

/** Ordinary consumers receive estimates, never hypothetical worlds. */
interface BeliefQueryView {
    val binding: BeliefSnapshotBinding
    val opponentHand: OpponentHandBeliefQueries
}
