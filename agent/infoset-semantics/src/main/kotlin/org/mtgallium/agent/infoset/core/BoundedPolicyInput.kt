package org.mtgallium.agent.infoset.core

import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable

// Persisted in policy identity; retain its value while retiring the bounded input DTO.
const val BOUNDED_POLICY_INPUT_SCHEMA_CURRENT: Int = 5

@Serializable
data class BoundedPolicyInputConfig(
    val recentEventLimit: Int = 64,
    val recentEventByteLimit: Int = 64 * 1024,
) {
    init {
        require(recentEventLimit > 0)
        require(recentEventByteLimit > 0)
    }
}

object BoundedPolicyInputCompiler {
    /** Shared feature window for live policies and sealed trajectory inputs. */
    fun recentEventWindow(
        history: List<PolicyHistoryEvent>,
        config: BoundedPolicyInputConfig = BoundedPolicyInputConfig(),
    ): PolicyRecentEventWindow {
        var bytes = 0
        var examined = 0
        val suffixReversed = mutableListOf<PolicyHistoryEvent>()
        for (event in history.asReversed()) {
            if (suffixReversed.size == config.recentEventLimit) break
            examined++
            val eventBytes = PolicyJson.format.encodeToString(PolicyHistoryEvent.serializer(), event)
                .toByteArray(StandardCharsets.UTF_8).size
            require(eventBytes <= config.recentEventByteLimit) {
                "One safe event requires $eventBytes bytes; window limit is ${config.recentEventByteLimit}"
            }
            if (bytes + eventBytes > config.recentEventByteLimit) break
            bytes += eventBytes
            suffixReversed += event
        }
        return PolicyRecentEventWindow(suffixReversed.asReversed(), examined, bytes)
    }
}

data class PolicyRecentEventWindow(
    val events: List<PolicyHistoryEvent>,
    val eventsExamined: Int,
    val serializedBytes: Int,
)
