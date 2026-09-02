package org.mtgallium.agent.infoset.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Rejects known private/referee fields and paths from player-safe JSON. */
object PublicArtifactPrivacy {
    private val forbiddenKeyFragments = listOf(
        "seed",
        "privileged",
        "chancetrace",
        "hiddenhands",
        "authoritativesemanticdigest",
        "replaydiagnostic",
    )

    fun requireSafeJson(encoded: String, artifactName: String) {
        val violations = mutableListOf<String>()
        inspect(PolicyJson.format.parseToJsonElement(encoded), "$", violations)
        require(violations.isEmpty()) {
            "$artifactName contains private/referee data: ${violations.distinct().sorted().joinToString()}"
        }
    }

    private fun inspect(element: JsonElement, path: String, violations: MutableList<String>) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                val lower = key.lowercase()
                if (forbiddenKeyFragments.any(lower::contains)) violations += "$path.$key"
                inspect(value, "$path.$key", violations)
            }
            is JsonArray -> element.forEachIndexed { index, value -> inspect(value, "$path[$index]", violations) }
            is JsonPrimitive -> if (element.isString) {
                val normalized = element.content.replace('\\', '/').lowercase()
                if ("/privileged/" in normalized || ".privileged." in normalized) violations += path
            }
        }
    }
}
