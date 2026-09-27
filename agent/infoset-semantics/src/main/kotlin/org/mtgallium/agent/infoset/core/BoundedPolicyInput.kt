package org.mtgallium.agent.infoset.core

import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable

// Persisted in policy identity; retain its value while retiring the bounded input DTO.
const val BOUNDED_POLICY_INPUT_SCHEMA_CURRENT: Int = 5

@Serializable @kotlinx.serialization.SerialName("org.mtgallium.agent.infoset.core.BoundedPolicyInputConfig")
data class PolicyInputLimits(
    val recentEventLimit: Int = 64,
    val recentEventByteLimit: Int = 64 * 1024,
) {
    init {
        require(recentEventLimit > 0)
        require(recentEventByteLimit > 0)
    }
}

object PolicyInputCompiler {
    /** Shared feature window for live policies and sealed trajectory inputs. */
    fun recentEventWindow(
        history: List<ObservedEvent>,
        config: PolicyInputLimits = PolicyInputLimits(),
    ): RecentEventWindow {
        var bytes = 0
        var examined = 0
        val suffixReversed = mutableListOf<ObservedEvent>()
        for (event in history.asReversed()) {
            if (suffixReversed.size == config.recentEventLimit) break
            examined++
            val eventBytes = CanonicalJson.format.encodeToString(ObservedEvent.serializer(), event)
                .toByteArray(StandardCharsets.UTF_8).size
            require(eventBytes <= config.recentEventByteLimit) {
                "One safe event requires $eventBytes bytes; window limit is ${config.recentEventByteLimit}"
            }
            if (bytes + eventBytes > config.recentEventByteLimit) break
            bytes += eventBytes
            suffixReversed += event
        }
        return RecentEventWindow(suffixReversed.asReversed(), examined, bytes)
    }
}

data class RecentEventWindow(
    val events: List<ObservedEvent>,
    val eventsExamined: Int,
    val serializedBytes: Int,
)
