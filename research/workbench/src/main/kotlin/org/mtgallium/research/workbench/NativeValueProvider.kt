package org.mtgallium.research.workbench

import java.util.ServiceLoader
import kotlinx.serialization.json.JsonObject
import org.mtgallium.agent.infoset.core.InformationStateEvaluator

/** Optional host value models for research statistics, discovered only when requested. */
interface JvmValueModelProvider {
    val names: Set<String>
    fun create(name: String, settings: JsonObject): InformationStateEvaluator
}

internal fun nativeValue(name: String, settings: JsonObject): InformationStateEvaluator {
    val providers = ServiceLoader.load(JvmValueModelProvider::class.java).filter { name in it.names }
    require(providers.size == 1) { "Expected one native value provider for '$name', found ${providers.size}" }
    return providers.single().create(name, settings)
}
