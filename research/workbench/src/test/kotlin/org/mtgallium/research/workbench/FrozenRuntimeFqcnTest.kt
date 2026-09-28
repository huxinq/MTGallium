package org.mtgallium.research.workbench

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Positive counterpart to module isolation tests: their forbidden names must identify real classes. */
class FrozenRuntimeFqcnTest {
    @Test
    fun cliAndServerEntrypointsExposePublicStaticMain() {
        for (name in listOf(
            "org.mtgallium.research.workbench.ResearchCliKt",
            "org.mtgallium.research.workbench.GameServer",
        )) {
            val entrypoint = Class.forName(name, false, javaClass.classLoader)
            val main = entrypoint.getMethod("main", Array<String>::class.java)
            assertTrue(java.lang.reflect.Modifier.isStatic(main.modifiers))
            assertEquals(Void.TYPE, main.returnType)
        }
    }

    @Test
    fun retainedIsolationTargetsExistOnTheWorkbenchClasspath() {
        val loader = javaClass.classLoader
        for (name in listOf(
            "org.mtgallium.agent.infoset.planning.SearchWorld",
            "org.mtgallium.agent.infoset.planning.InformationSetSearch",
            "org.mtgallium.agent.infoset.planning.ParticleBelief",
            "org.mtgallium.agent.infoset.planning.LeafValueSource",
            "org.mtgallium.agent.infoset.planning.RootActionSelector",
            "org.mtgallium.agent.infoset.planning.RootActionSelection",
            "org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld",
            "com.wingedsheep.engine.core.GameAction",
            "com.wingedsheep.engine.state.GameState",
            "org.mtgallium.agent.value.MaterialEvaluator",
            "org.mtgallium.agent.argentum.policy.LivePolicySession",
        )) {
            // Loading without initialization checks the JVM name without constructing an engine or policy.
            assertEquals(name, Class.forName(name, false, loader).name)
        }
    }

    @Test
    fun providerResourceNameAndPublicTestProviderResolveTogether() {
        val loader = javaClass.classLoader
        for ((contractName, providerName) in listOf(
            "org.mtgallium.research.workbench.JvmPolicyProvider" to
                "org.mtgallium.research.workbench.TestNativePolicies",
        )) {
            val contract = Class.forName(contractName, false, loader)
            val provider = Class.forName(providerName, false, loader)
            assertTrue(contract.isAssignableFrom(provider))
            val expected = "$providerName\n".toByteArray(UTF_8)
            val resources = Collections.list(loader.getResources("META-INF/services/$contractName"))
            assertTrue(resources.any { resource ->
                resource.openStream().use { it.readBytes().contentEquals(expected) }
            }, "The literal provider resource and its entry must remain loadable together: $contractName")
        }
    }
}
