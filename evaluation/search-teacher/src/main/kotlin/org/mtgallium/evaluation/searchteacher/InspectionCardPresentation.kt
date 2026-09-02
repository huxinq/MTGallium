package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardFace
import com.wingedsheep.sdk.model.CardLayout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.mtgallium.agent.infoset.core.PerspectiveEventDetail
import org.mtgallium.agent.infoset.core.PolicyInspectionBundle
import org.mtgallium.agent.infoset.core.PolicyInspectionCardImage
import org.mtgallium.agent.infoset.core.PolicyInspectionPresentation
import org.mtgallium.agent.infoset.core.SemanticChoice

/**
 * Resolves display art only after the safe inspection bundle has been built. The safe collector has
 * no authoritative game-state input, which makes hidden-world changes structurally unable to alter
 * the public art catalog.
 */
internal class InspectionCardPresentationResolver(
    private val registry: CardRegistry,
    baseCardNames: Set<String>,
) {
    private val alternateFaces: Map<String, FaceDefinition> = baseCardNames
        .asSequence()
        .mapNotNull(registry::getCard)
        .flatMap { definition ->
            sequence {
                definition.backFace?.let { yield(it.name to FaceDefinition.Card(it)) }
                definition.cardFaces.forEach { yield(it.name to FaceDefinition.Face(definition, it)) }
            }
        }
        .distinctBy { it.first }
        .toMap()

    fun safe(bundle: PolicyInspectionBundle): PolicyInspectionPresentation {
        val requests = linkedMapOf<String, ImageRequest>()

        fun requestName(name: String?, required: Boolean = true) {
            val value = name?.takeIf(String::isNotBlank) ?: return
            requests.merge("name:$value", ImageRequest("name:$value", value, emptySet(), required)) { old, next ->
                old.copy(required = old.required || next.required)
            }
        }

        fun requestChoice(choice: SemanticChoice) {
            requestName(choice.display.sourceName, required = false)
            choice.display.targetNames.forEach { requestName(it, required = false) }
        }

        bundle.frames.forEach { frame ->
            frame.observation.zones.flatMap { it.cards }.forEach { card ->
                if (!card.faceDown) {
                    requestName(card.name)
                    card.definitionId?.let { definitionId ->
                        requests.putIfAbsent(
                            "definition:$definitionId",
                            ImageRequest("definition:$definitionId", card.name, card.subtypes, required = true),
                        )
                    }
                }
            }
            frame.observation.stack.forEach { requestName(it.name) }
            requestName(frame.observation.pendingDecision?.sourceName, required = false)
            frame.candidates.forEach { requestChoice(it) }
            frame.search?.let { search ->
                requestChoice(search.chosen)
                requestChoice(search.heuristicChoice)
                search.candidates.forEach { requestChoice(it.choice) }
                search.expansion.candidates.forEach { requestChoice(it) }
            }
            frame.knowledge.deckCardCounts.values.forEach { counts -> counts.keys.forEach { requestName(it) } }
            frame.knowledge.zones.forEach { zone -> zone.knownCardCounts.keys.forEach { requestName(it) } }
            frame.knowledge.knownObjects.forEach { requestName(it.cardName) }
            frame.knowledge.knownLibraryOrders.flatMap { it.top }.filterNotNull().forEach { requestName(it) }
            frame.knowledge.unlocatedCardCounts.values.forEach { counts -> counts.keys.forEach { requestName(it) } }
        }

        bundle.ledger.forEach { event ->
            collectDetailNames(event.detail, ::requestName)
            (event.payload["sourceName"] as? JsonPrimitive)?.content?.let { requestName(it, required = false) }
            (event.payload["targetNames"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?.forEach { requestName(it, required = false) }
        }

        return resolve(requests.values)
    }

    fun privileged(bundle: PrivilegedInspectionBundle): PolicyInspectionPresentation {
        val names = bundle.frames.asSequence().flatMap { frame ->
            (frame.snapshot.hiddenHands.values.flatten() + frame.snapshot.libraries.values.flatten()).asSequence()
        }.filter(String::isNotBlank).distinct().sorted().toList()
        return resolve(names.map { ImageRequest("name:$it", it, emptySet(), required = true) })
    }

    private fun resolve(requests: Collection<ImageRequest>): PolicyInspectionPresentation {
        val images = mutableListOf<PolicyInspectionCardImage>()
        val unresolved = sortedSetOf<String>()
        requests.sortedBy { it.key }.forEach { request ->
            val resolved = resolve(request)
            if (resolved == null || !resolved.imageUri.startsWith(SCRYFALL_IMAGE_PREFIX)) {
                if (request.required) unresolved += request.cardName
            } else {
                images += PolicyInspectionCardImage(
                    key = request.key,
                    cardName = request.cardName,
                    imageUri = resolved.imageUri,
                    rotationDegrees = resolved.rotationDegrees,
                )
            }
        }
        return PolicyInspectionPresentation(images.sortedBy { it.key }, unresolved.toList())
    }

    private fun resolve(request: ImageRequest): ResolvedImage? {
        val exactDefinition = request.key.removePrefix("definition:")
            .takeIf { request.key.startsWith("definition:") }
            ?.let(registry::getCard)
        if (exactDefinition != null) return exactDefinition.resolveImage(request.subtypes)

        registry.getCard(request.cardName)?.let { return it.resolveImage(request.subtypes) }
        return when (val face = alternateFaces[request.cardName]) {
            is FaceDefinition.Card -> face.definition.resolveImage(request.subtypes)
            is FaceDefinition.Face -> face.resolveImage()
            null -> null
        }
    }

    private fun CardDefinition.resolveImage(subtypes: Set<String>): ResolvedImage? {
        val image = metadata.imageUriByCreatureSubtype.entries
            .firstOrNull { (subtype) -> subtype in subtypes }
            ?.value
            ?: metadata.imageUri
            ?: return null
        return ResolvedImage(image, rotation(metadata.imageRotation, isLandscapePrint))
    }

    private fun FaceDefinition.Face.resolveImage(): ResolvedImage? {
        val image = face.imageUri ?: parent.metadata.imageUri ?: return null
        val landscape = parent.layout == CardLayout.SPLIT || face.typeLine.isBattle
        return ResolvedImage(image, rotation(parent.metadata.imageRotation, landscape))
    }

    private fun rotation(base: Int, landscape: Boolean): Int {
        val normalized = ((base + if (landscape) 90 else 0) % 360 + 360) % 360
        return normalized.takeIf { it in CARDINAL_ROTATIONS } ?: 0
    }

    private sealed interface FaceDefinition {
        data class Card(val definition: CardDefinition) : FaceDefinition
        data class Face(val parent: CardDefinition, val face: CardFace) : FaceDefinition
    }

    private data class ImageRequest(
        val key: String,
        val cardName: String,
        val subtypes: Set<String>,
        val required: Boolean,
    )

    private data class ResolvedImage(val imageUri: String, val rotationDegrees: Int)

    private companion object {
        const val SCRYFALL_IMAGE_PREFIX = "https://cards.scryfall.io/"
        val CARDINAL_ROTATIONS = setOf(0, 90, 180, 270)
    }
}

private fun collectDetailNames(
    detail: PerspectiveEventDetail?,
    add: (String?, Boolean) -> Unit,
) {
    when (detail) {
        is PerspectiveEventDetail.ZoneChange -> add(detail.cardName, true)
        is PerspectiveEventDetail.Draw -> detail.knownCardNames.forEach { add(it, true) }
        is PerspectiveEventDetail.Reveal -> detail.cardNames.forEach { add(it, true) }
        is PerspectiveEventDetail.Look -> detail.cardNames.forEach { add(it, true) }
        is PerspectiveEventDetail.LibraryReorder -> detail.orderedCardNames.forEach { add(it, true) }
        is PerspectiveEventDetail.Damage -> {
            add(detail.sourceName, false)
            add(detail.targetName, false)
        }
        is PerspectiveEventDetail.CounterChange -> add(detail.objectName, false)
        is PerspectiveEventDetail.ObjectState -> add(detail.objectName, false)
        is PerspectiveEventDetail.Causal -> {
            add(detail.sourceName, false)
            detail.targetNames.forEach { add(it, false) }
        }
        is PerspectiveEventDetail.ResourceChange -> add(detail.sourceName, false)
        is PerspectiveEventDetail.CharacteristicChange -> {
            add(detail.objectName, false)
            add(detail.sourceName, false)
        }
        is PerspectiveEventDetail.Combat -> detail.assignments.values.flatten().forEach { add(it, false) }
        is PerspectiveEventDetail.Choice,
        is PerspectiveEventDetail.Shuffle,
        is PerspectiveEventDetail.LifeChange,
        is PerspectiveEventDetail.TurnStructure,
        is PerspectiveEventDetail.Terminal,
        is PerspectiveEventDetail.UnsupportedVisibleTransition,
        null,
        -> Unit
    }
}
