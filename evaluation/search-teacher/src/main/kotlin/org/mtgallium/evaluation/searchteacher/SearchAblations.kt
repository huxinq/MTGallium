package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Path
import java.time.Instant
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.BeliefArchitecture

internal class SearchMethodAblations(
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val profile: FrozenSearchProfile,
    private val baseSeed: Long,
    private val checkpointRoot: Path? = null,
) {
    fun run(pairCount: Int, workerThreads: Int): SearchMethodAblationReport {
        data class Specification(
            val id: String,
            val planner: SearchPlannerKind,
            val beliefMode: BeliefMode,
            val beliefArchitecture: BeliefArchitecture,
            val deployable: Boolean = false,
        )
        val specifications = listOf(
            Specification(
                "no-search-heuristic",
                SearchPlannerKind.NO_SEARCH_HEURISTIC,
                BeliefMode.CONSISTENCY_ONLY_V1,
                BeliefArchitecture.SEQUENTIAL_B_V1,
            ),
            Specification(
                "privileged-o-shared-tree",
                SearchPlannerKind.PERFECT_INFORMATION_ORACLE,
                BeliefMode.CONSISTENCY_ONLY_V1,
                BeliefArchitecture.PRIVILEGED_O_V1,
            ),
            Specification(
                "independent-determinization",
                SearchPlannerKind.INDEPENDENT_DETERMINIZATION,
                BeliefMode.CONSISTENCY_ONLY_V1,
                BeliefArchitecture.SNAPSHOT_A_V1,
            ),
            Specification(
                "snapshot-a-shared-tree",
                SearchPlannerKind.SHARED_TREE,
                BeliefMode.CONSISTENCY_ONLY_V1,
                BeliefArchitecture.SNAPSHOT_A_V1,
            ),
            Specification(
                "sequential-b-shared-tree",
                SearchPlannerKind.SHARED_TREE,
                BeliefMode.CONSISTENCY_ONLY_V1,
                BeliefArchitecture.SEQUENTIAL_B_V1,
                deployable = true,
            ),
            Specification(
                "hybrid-c-shared-tree",
                SearchPlannerKind.SHARED_TREE,
                BeliefMode.CONSISTENCY_ONLY_V1,
                BeliefArchitecture.HYBRID_C_V1,
            ),
            Specification(
                "sequential-b-policy-conditioned",
                SearchPlannerKind.SHARED_TREE,
                BeliefMode.POLICY_CONDITIONED_V1,
                BeliefArchitecture.SEQUENTIAL_B_V1,
            ),
        )
        val methods = specifications.map { specification ->
            val arena = SearchTeacherArena(
                registry = registry,
                manifest = manifest,
                profile = profile,
                baseSeed = baseSeed,
                beliefMode = specification.beliefMode,
                beliefArchitecture = specification.beliefArchitecture,
                searchPlanner = specification.planner,
            )
            SearchMethodAblation(
                id = specification.id,
                planner = specification.planner,
                beliefMode = specification.beliefMode,
                beliefArchitecture = specification.beliefArchitecture,
                deployable = specification.deployable,
                arena = pairedArena(
                    arena = arena,
                    profileId = "${profile.id}:${specification.id}",
                    opponent = ArenaPolicyKind.HEURISTIC,
                    pairCount = pairCount,
                    baseSeed = baseSeed,
                    workerThreads = workerThreads,
                    checkpointRoot = checkpointRoot,
                ),
            )
        }
        return SearchMethodAblationReport(
            generatedAtUtc = Instant.now().toString(),
            profileId = profile.id,
            pairCountPerMethod = pairCount,
            methods = methods,
        )
    }
}
