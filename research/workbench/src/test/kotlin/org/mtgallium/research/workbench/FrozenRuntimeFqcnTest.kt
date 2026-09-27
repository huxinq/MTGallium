package org.mtgallium.research.workbench

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Positive counterpart to module isolation tests: their forbidden names must identify real classes. */
class FrozenRuntimeFqcnTest {
    @Test
    fun retainedIsolationTargetsExistOnTheWorkbenchClasspath() {
        val loader = javaClass.classLoader
        for (name in listOf(
            "org.mtgallium.agent.infoset.core.SearchWorld",
            "org.mtgallium.agent.infoset.core.InformationSetSearch",
            "org.mtgallium.agent.infoset.core.ParticleBelief",
            "org.mtgallium.agent.infoset.core.LeafValueSource",
            "org.mtgallium.agent.infoset.core.RootActionSelector",
            "org.mtgallium.agent.infoset.core.RootActionSelection",
            "org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld",
            "com.wingedsheep.engine.core.GameAction",
            "com.wingedsheep.engine.state.GameState",
            "org.mtgallium.agent.monored.MonoRedInformationEvaluator",
            "org.mtgallium.agent.argentum.policy.LivePolicySession",
        )) {
            // Loading without initialization checks the JVM name without constructing an engine or policy.
            assertEquals(name, Class.forName(name, false, loader).name)
        }
    }

    @Test
    fun providerResourceNameAndPublicTestProviderResolveTogether() {
        val loader = javaClass.classLoader
        val contractName = "org.mtgallium.research.workbench.NativePolicyProvider"
        val providerName = "org.mtgallium.research.workbench.TestNativePolicies"
        val contract = Class.forName(contractName, false, loader)
        val provider = Class.forName(providerName, false, loader)
        assertTrue(contract.isAssignableFrom(provider))
        val expected = "$providerName\n".toByteArray(UTF_8)
        val resources = Collections.list(loader.getResources("META-INF/services/$contractName"))
        assertTrue(resources.any { resource ->
            resource.openStream().use { it.readBytes().contentEquals(expected) }
        }, "The literal provider resource and its entry must remain loadable together")
    }
}
