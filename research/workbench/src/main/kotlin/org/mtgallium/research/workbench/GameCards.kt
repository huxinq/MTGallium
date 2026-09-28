package org.mtgallium.research.workbench

import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.mtg.sets.tokens.PredefinedTokens
import com.wingedsheep.sdk.model.CardDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.json.Json

private fun cardResource(name: String) = "game-cards/" +
    Base64.getUrlEncoder().withoutPadding().encodeToString(name.toByteArray(Charsets.UTF_8)) + ".json"

private fun readCards(resource: String): List<CardDefinition> =
    requireNotNull(CardRegistry::class.java.classLoader.getResourceAsStream(resource)) {
        "Missing generated card resource: $resource"
    }.bufferedReader().use { Json.decodeFromString(it.readText()) }

/** Load deck cards without initializing Argentum's catalog of card-building classes. */
fun buildRegistry(cardNames: Collection<String> = emptyList()): CardRegistry = CardRegistry().apply {
    register(readCards("game-cards/basics-and-tokens.json"))
    loadCards(cardNames)
}

internal fun CardRegistry.loadCards(names: Collection<String>) = synchronized(this) {
    names.distinct().filterNot(::hasCard).forEach { name ->
        val resource = cardResource(name.substringBefore('#'))
        require(CardRegistry::class.java.classLoader.getResource(resource) != null) {
            "Card not found in registry: $name"
        }
        register(readCards(resource))
        requireCard(name)
    }
}

/** A privileged replay contains the original deck identities, including cards still in libraries. */
internal fun CardRegistry.loadCards(state: GameState) = loadCards(state.entities.values
    .filterNot { it.has<TokenComponent>() }.mapNotNull { it.get<CardComponent>()?.cardDefinitionId })

/** Build-time only: preserve the catalog's overwrite order and verify every serialized definition. */
object GenerateGameCards {
    @JvmStatic fun main(args: Array<String>) {
        val registry = CardRegistry()
        val basicsAndTokens = mutableSetOf<String>()
        fun register(cards: List<CardDefinition>, bootstrap: Boolean = false) {
            registry.register(cards)
            if (bootstrap) basicsAndTokens += cards.map { it.name }
        }
        register(PredefinedTokens.allTokens, true)
        MtgSetCatalog.all.forEach { set ->
            register(set.cards)
            register(set.basicLands, true)
            set.basicLandsFallback?.let { register(it.basicLands, true) }
        }
        val root = Path.of(args.single())
        fun write(resource: String, cards: List<CardDefinition>) {
            val encoded = Json.encodeToString(cards)
            check(Json.decodeFromString<List<CardDefinition>>(encoded) == cards) { "Card round-trip failed: $resource" }
            val path = root.resolve(resource)
            Files.createDirectories(path.parent)
            Files.writeString(path, encoded)
        }
        fun definitions(name: String) = registry.getCardsByName(name) + registry.requireCard(name)
        registry.allCardNames().forEach { name ->
            write(cardResource(name), definitions(name))
        }
        write("game-cards/basics-and-tokens.json", basicsAndTokens.flatMap(::definitions))
    }
}
