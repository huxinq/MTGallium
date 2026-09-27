package org.mtgallium.research.workbench

import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.infoset.planning.*
import org.mtgallium.agent.infoset.argentum.ActionGenerationSpecification
import org.mtgallium.agent.argentum.policy.ObservedActionLikelihood
import org.mtgallium.agent.value.*
import org.mtgallium.agent.neural.ByteTokenSchema
import org.mtgallium.agent.neural.DecisionByteTokens
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.sdk.model.Deck
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mtgallium.agent.argentum.policy.SearchPolicyConfig
import org.mtgallium.agent.argentum.policy.PolicyIdentity
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.HistoryEventOrdering
import org.mtgallium.agent.infoset.argentum.HistoryObjectReferencing
import org.mtgallium.agent.infoset.core.BeliefApproximation
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.CanonicalJson
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.infoset.core.UniformOpponentPolicy
import org.mtgallium.agent.value.ValueFeatures
import org.mtgallium.agent.neural.InformationStateByteEncoder
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/** Public-fixture byte baselines for frozen vocabularies, identities and model inputs. */
class FrozenPublicByteGoldensTest {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test
    fun frozenVocabularyPreservesSerializerNamesAndWireTokens() {
        val names = listOf(
            ReturnSource.serializer().descriptor,
            LeafEvaluationMethod.serializer().descriptor,
            RolloutCutoff.serializer().descriptor,
            QuiescencePassRule.serializer().descriptor,
            LeafEvaluationConfig.serializer().descriptor,
            LeafEvaluationDiagnostic.serializer().descriptor,
            RolloutTurnHorizon.serializer().descriptor,
            InformationSetSearchConfig.serializer().descriptor,
            RootActionStatistics.serializer().descriptor,
            ReturnSourceCounts.serializer().descriptor,
            InformationSetSearchDiagnostics.serializer().descriptor,
            InformationSetSearchResult.serializer().descriptor,
            SingletonMenuShortcutConfig.serializer().descriptor,
            ObservedEvent.serializer().descriptor,
            ObservedEventKind.serializer().descriptor,
            ObservedEventDetail.serializer().descriptor,
            HistoryHashChain.serializer().descriptor,
            ObservedEventDetail.Choice.serializer().descriptor,
            ObservedEventDetail.ZoneChange.serializer().descriptor,
            ObservedEventDetail.Draw.serializer().descriptor,
            ObservedEventDetail.Reveal.serializer().descriptor,
            ObservedEventDetail.Look.serializer().descriptor,
            ObservedEventDetail.LibraryReorder.serializer().descriptor,
            ObservedEventDetail.Shuffle.serializer().descriptor,
            ObservedEventDetail.LifeChange.serializer().descriptor,
            ObservedEventDetail.Damage.serializer().descriptor,
            ObservedEventDetail.CounterChange.serializer().descriptor,
            ObservedEventDetail.ObjectState.serializer().descriptor,
            ObservedEventDetail.Causal.serializer().descriptor,
            ObservedEventDetail.ResourceChange.serializer().descriptor,
            ObservedEventDetail.CharacteristicChange.serializer().descriptor,
            ObservedEventDetail.Combat.serializer().descriptor,
            ObservedEventDetail.TurnStructure.serializer().descriptor,
            ObservedEventDetail.Terminal.serializer().descriptor,
            ObservedEventDetail.UnsupportedVisibleTransition.serializer().descriptor,
            PlayerKnowledge.serializer().descriptor,
            ZoneKnowledge.serializer().descriptor,
            KnownObject.serializer().descriptor,
            PlayerView.serializer().descriptor,
            ZoneView.serializer().descriptor,
            ObjectView.serializer().descriptor,
            StackObjectView.serializer().descriptor,
            PendingDecisionView.serializer().descriptor,
            CombatView.serializer().descriptor,
            PendingDecisionOptions.serializer().descriptor,
            PendingDecisionOptions.Targets.serializer().descriptor,
            PendingDecisionOptions.Cards.serializer().descriptor,
            PendingDecisionOptions.YesNo.serializer().descriptor,
            PendingDecisionOptions.BatchYesNo.serializer().descriptor,
            PendingDecisionOptions.Modes.serializer().descriptor,
            PendingDecisionOptions.Colors.serializer().descriptor,
            PendingDecisionOptions.Number.serializer().descriptor,
            PendingDecisionOptions.Distribution.serializer().descriptor,
            PendingDecisionOptions.Order.serializer().descriptor,
            PendingDecisionOptions.Piles.serializer().descriptor,
            PendingDecisionOptions.Options.serializer().descriptor,
            PendingDecisionOptions.Replacement.serializer().descriptor,
            PendingDecisionOptions.LibrarySearch.serializer().descriptor,
            PendingDecisionOptions.LibraryReorder.serializer().descriptor,
            PendingDecisionOptions.DamageAssignment.serializer().descriptor,
            PendingDecisionOptions.CombatResolution.serializer().descriptor,
            PendingDecisionOptions.ManaSources.serializer().descriptor,
            PendingDecisionOptions.BudgetModal.serializer().descriptor,
            KnownLibraryOrder.serializer().descriptor,
            AttackerView.serializer().descriptor,
            BlockerView.serializer().descriptor,
            ManaPoolView.serializer().descriptor,
            RestrictedManaView.serializer().descriptor,
            EventAudience.serializer().descriptor,
            EventAudienceScope.serializer().descriptor,
            ActionOmissionReason.serializer().descriptor,
            PolicyInputLimits.serializer().descriptor,
            ActionGenerationSpecification.serializer().descriptor,
            ByteTokenSchema.serializer().descriptor,
            DecisionByteTokens.serializer().descriptor,
            KernelFeatureVector.serializer().descriptor,
            KernelActionFeatures.serializer().descriptor,
            KernelTrainingRoot.serializer().descriptor,
            KernelRidgeActionModel.serializer().descriptor,
            ResearchGameConfig.serializer().descriptor,
            ActionMenu.serializer().descriptor,
            ActionSpaceProfile.serializer().descriptor,
            BeliefApproximation.serializer().descriptor,
            BeliefMode.serializer().descriptor,
            HistoryEventOrdering.serializer().descriptor,
            ObservedActionLikelihood.serializer().descriptor,
            InverseLink.serializer().descriptor,
            ChanceControlVariateConfig.serializer().descriptor,
            LuckEvent.serializer().descriptor,
            LinearWeights.serializer().descriptor,
            ValueInputError.serializer().descriptor,
            MaterialWeights.serializer().descriptor,
            MaterialPermanentFeatures.serializer().descriptor,
            MaterialFeatures.serializer().descriptor,
            SemanticChoice.serializer().descriptor,
        ).map { it.serialName }
        val tokens = listOf(
            wireTokens(ActionSpaceProfile.serializer(), ActionSpaceProfile.entries),
            wireTokens(BeliefApproximation.serializer(), BeliefApproximation.entries),
            wireTokens(BeliefMode.serializer(), BeliefMode.entries),
            wireTokens(LeafEvaluationMethod.serializer(), LeafEvaluationMethod.entries),
            wireTokens(QuiescencePassRule.serializer(), QuiescencePassRule.entries),
            wireTokens(HistoryEventOrdering.serializer(), HistoryEventOrdering.entries),
            wireTokens(ObservedActionLikelihood.serializer(), ObservedActionLikelihood.entries),
            wireTokens(InverseLink.serializer(), InverseLink.entries),
            wireTokens(ReturnSource.serializer(), ReturnSource.entries),
            wireTokens(EventAudienceScope.serializer(), EventAudienceScope.entries),
            wireTokens(ActionOmissionReason.serializer(), ActionOmissionReason.entries),
            MenuSource.entries.joinToString(",") { it.name },
            HistoryObjectReferencing.entries.joinToString(",") { it.name },
            RootActionStatistics.serializer().descriptor.let { d ->
                (0 until d.elementsCount).joinToString(",", transform = d::getElementName)
            },
            LuckEvent.serializer().descriptor.getElementName(6),
            InformationSetSearchDiagnostics.serializer().descriptor.let { d ->
                d.getElementName(d.getElementIndex("unsettledLeafEvaluations"))
            },
        )
        val expected = requireNotNull(javaClass.getResource("/frozen-public-byte-goldens/vocabulary.txt")).readText()
        kotlin.test.assertEquals(expected, (names + tokens).joinToString("\n", postfix = "\n"))
    }

    private fun <T> wireTokens(serializer: kotlinx.serialization.KSerializer<T>, values: List<T>): String =
        values.joinToString(",") { value ->
            Json.encodeToString(serializer, value).also { encoded ->
                kotlin.test.assertEquals(value, Json.decodeFromString(serializer, encoded))
            }
        }

    private val decks = mapOf("p0" to mapOf("Mountain" to 8), "p1" to mapOf("Mountain" to 8))

    @Test
    fun identityBytes() {
        val base = SearchPolicyConfig(particles = 4, simulations = 8, maxPolicyDecisions = 4)
        val identities = sortedMapOf(
            "default" to PolicyIdentity.identity(base, decks, UniformOpponentPolicy),
            "snapshot-a" to PolicyIdentity.identity(base.copy(beliefArchitecture = BeliefApproximation.SNAPSHOT_A_V1),
                decks, UniformOpponentPolicy),
            "privileged-o" to PolicyIdentity.identity(base.copy(beliefArchitecture = BeliefApproximation.PRIVILEGED_O_V1),
                decks, UniformOpponentPolicy),
            "conditioned" to PolicyIdentity.identity(base.copy(beliefMode = BeliefMode.POLICY_CONDITIONED_V1),
                decks, UniformOpponentPolicy),
        )
        verifyOrCapture("identity", identities.mapValues { it.value.toByteArray(UTF_8) })
    }

    @Test
    fun publicBehaviorBytes() {
        val world = publicWorld(HistoryEventOrdering.LEGACY_ENGINE_ORDER_V1,
            HistoryObjectReferencing.LEGACY_SNAPSHOT_V1)
        val site = world.decisionContext().site()
        val information = site.information()
        val tensors = InformationStateByteEncoder().decision(site)
        val keys = ValueFeatures.compile(information, "p0").values.keys.sorted()
        assertTrue(keys.isNotEmpty())
        val outputs = sortedMapOf<String, ByteArray>(
            "value/public-fixture-keys" to keys.joinToString("\n", postfix = "\n").toByteArray(UTF_8),
            "tensor/view-text" to untoken(tensors.view),
            "tensor/flags" to "${tensors.rulesExhaustive},${tensors.profileExhaustive}\n".toByteArray(UTF_8),
            // The public API returns hashed vectors; it does not expose pre-hash feature names.
            "kernel/public-fixture-vectors" to researchJson.encodeToString(kernelActionFeatures(site)).toByteArray(UTF_8),
        )
        tensors.actions.forEachIndexed { index, action ->
            outputs["tensor/action-text-%03d".format(index)] = untoken(action)
        }
        // A visible land move makes this an ordinary history corpus; it is not a qualified
        // simultaneous-untap witness. That mechanism retains its focused regression tests.
        for (order in listOf(HistoryEventOrdering.LEGACY_ENGINE_ORDER_V1,
            HistoryEventOrdering.QUALIFIED_TURN_UNTAP_V2)) {
            for (references in listOf(HistoryObjectReferencing.LEGACY_SNAPSHOT_V1,
                HistoryObjectReferencing.QUALIFIED_OBSERVED_OBJECTS_V2)) {
                val historyWorld = publicWorld(order, references)
                var landPlays = 0
                for (step in 0 until 48) {
                    if (historyWorld.actorToAct() == null) break
                    val candidates = historyWorld.expandChoices().candidates
                    val choice = candidates.firstOrNull {
                        it.operationFamily == SemanticOperationFamily.PLAY_LAND
                    } ?: candidates.firstOrNull {
                        it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
                    } ?: candidates.firstOrNull() ?: error("No choice in public history fixture at step $step")
                    check(historyWorld.step(choice).accepted) { "Rejected public history fixture choice $step" }
                    if (choice.operationFamily == SemanticOperationFamily.PLAY_LAND) landPlays++
                }
                check(landPlays > 0) { "Public history fixture never played a visible land" }
                for (viewer in listOf("p0", "p1")) {
                    val state = historyWorld.informationState(viewer)
                    check(state.history.isNotEmpty()) { "History fixture produced no events for $viewer" }
                    val prefix = "history/${order.name}/${references.name}/$viewer"
                    outputs["$prefix/events"] = CanonicalJson.format.encodeToString(state.history).toByteArray(UTF_8)
                    outputs["$prefix/commitment"] = CanonicalJson.format.encodeToString(state.historyCommitment).toByteArray(UTF_8)
                }
            }
        }
        verifyOrCapture("behavior", outputs)
    }

    private fun publicWorld(order: HistoryEventOrdering,
        references: HistoryObjectReferencing): ArgentumSearchWorld {
        val registry = buildRegistry()
        val environment = GameEnvironment.create(registry)
        environment.reset(GameConfig(
            players = listOf(PlayerConfig("A", Deck.of("Mountain" to 8)),
                PlayerConfig("B", Deck.of("Mountain" to 8))),
            startingHandSize = 2, skipMulligans = true, startingPlayerIndex = 0, seed = 61L,
        ))
        return ArgentumSearchWorld.create(environment, "frozen-public-61", 61L, 61L,
            knownDecks = decks, historyEventOrder = order, historyObjectReference = references)
    }

    private fun untoken(tokens: List<Int>): ByteArray {
        require(tokens.all { it in 1..256 })
        return tokens.map { (it - 1).toByte() }.toByteArray()
    }

    /** Compares each entry with the committed golden; MTG_CAPTURE_GOLDENS=1 writes a candidate instead. */
    private fun verifyOrCapture(group: String, entries: Map<String, ByteArray>) {
        val current = entries.toSortedMap().mapValues { Base64.getEncoder().encodeToString(it.value) }
        if (System.getenv("MTG_CAPTURE_GOLDENS") == "1") {
            val output = Path.of("build", "frozen-public-byte-goldens", "$group.json")
            Files.createDirectories(output.parent)
            Files.writeString(output, JsonObject(current.mapValues { JsonPrimitive(it.value) }).toString())
            fail("Captured $output; review it before replacing the golden")
        }
        val pinned = Json.parseToJsonElement(requireNotNull(
            javaClass.getResource("/frozen-public-byte-goldens/$group.json")) { "Missing $group golden" }.readText())
            .jsonObject.mapValues { it.value.jsonPrimitive.content }
        val changed = (pinned.keys + current.keys).sorted().filter { pinned[it] != current[it] }
        assertTrue(changed.isEmpty(), "$group byte golden changed at $changed")
    }
}
