package org.mtgallium.agent.infoset.planning

import org.mtgallium.agent.infoset.core.*

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable @SerialName("org.mtgallium.agent.infoset.core.SingletonSelectionConfig")
data class SingletonMenuShortcutConfig(
    val schemaVersion: Int = 1,
    val enabled: Boolean = false,
) {
    init { require(schemaVersion == 1) }
}
