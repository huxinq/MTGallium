package org.mtgallium.agent.searchteacher

import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource

/** Teacher-owned calibration cells; the core search contract only validates individual configurations. */
object SearchTeacherLeafConfigurations {
    /** Frozen production/calibration cells. Experimental successors must pass their own gate. */
    val supported: List<LeafEvaluationConfig> = listOf(
        LeafEvaluationConfig(LeafStateSource.CURRENT_INFORMATION_STATE, LeafEvaluator.MTGALLIUM_VISIBLE_V2),
        LeafEvaluationConfig(LeafStateSource.CURRENT_SAMPLED_WORLD, LeafEvaluator.MTGALLIUM_VISIBLE_V2),
        LeafEvaluationConfig(LeafStateSource.CURRENT_SAMPLED_WORLD, LeafEvaluator.ARGENTUM_BOARD_V1),
        LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT, LeafEvaluator.MTGALLIUM_VISIBLE_V2),
        LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT, LeafEvaluator.ARGENTUM_BOARD_V1),
    )

    val experimental: List<LeafEvaluationConfig> = LeafStateSource.entries.map { source ->
        LeafEvaluationConfig(source, LeafEvaluator.MTGALLIUM_TACTICAL_V3)
    }
}
