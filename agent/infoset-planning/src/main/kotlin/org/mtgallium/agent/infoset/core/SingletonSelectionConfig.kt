package org.mtgallium.agent.infoset.core

import kotlinx.serialization.Serializable

@Serializable
data class SingletonSelectionConfig(
    val schemaVersion: Int = 1,
    val enabled: Boolean = false,
) {
    init { require(schemaVersion == 1) }
}
