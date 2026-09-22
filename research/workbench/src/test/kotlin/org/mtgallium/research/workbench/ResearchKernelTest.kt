package org.mtgallium.research.workbench

import java.nio.file.Files
import kotlin.test.*
import kotlinx.serialization.json.*

class ResearchKernelTest {
    private fun vector(value: Double) = RootActionKernelVector(listOf(0), listOf(value))
    private fun features(state: Double, action: Double) = RootActionKernelFeatures(vector(state), vector(action))
    private fun root(id: String, state: Double, offset: Double = 0.0) = RootActionKernelTrainingRoot(id, id,
        listOf(features(state, -1.0), features(state, 1.0)), listOf(-state * .3 + offset, state * .3 + offset))

    @Test fun `interaction learns opposite preferences and survives a plain JSON roundtrip`() {
        val model = fitRootActionKernel(listOf(root("positive", 1.0), root("negative", -1.0)), 1e-6)
        assertEquals(.15, model.score(features(.5, 1.0)), 1e-5)
        assertTrue(model.score(features(-.5, -1.0)) > model.score(features(-.5, 1.0)))
        assertEquals(model, researchJson.decodeFromString<RootActionKernelModel>(researchJson.encodeToString(model)))
    }

    @Test fun `root offsets and within-group replication do not change preferences`() {
        val roots = listOf(root("positive", 1.0), root("negative", -1.0))
        val model = fitRootActionKernel(roots, .01)
        val shifted = fitRootActionKernel(listOf(root("positive", 1.0, 20.0), root("negative", -1.0, -20.0)), .01)
        val replicated = fitRootActionKernel(roots + roots.first().copy(rootId = "copy"), .01)
        for (state in listOf(-1.0, .3, 1.0)) for (action in listOf(-1.0, 1.0)) {
            val input = features(state, action)
            assertEquals(model.score(input), shifted.score(input), 1e-12)
            assertEquals(model.score(input), replicated.score(input), 1e-12)
        }
    }

    @Test fun `explicit mass is used rather than silently normalized`() {
        val roots = listOf(root("a", 1.0), root("b", -1.0))
        val doubled = fitRootActionKernel(roots, .1, roots.associate { it.rootId to listOf(.5, .5) })
        val halfRidge = fitRootActionKernel(roots, .05)
        assertEquals(halfRidge.score(features(.4, 1.0)), doubled.score(features(.4, 1.0)), 1e-12)
    }

    @Test fun `singular features are regularized but invalid numerical inputs remain errors`() {
        val roots = listOf(root("a", 0.0), root("b", 0.0))
        assertEquals(0.0, fitRootActionKernel(roots).score(features(1.0, 1.0)))
        assertFailsWith<IllegalArgumentException> { fitRootActionKernel(roots, 0.0) }
        assertFailsWith<IllegalArgumentException> { fitRootActionKernel(roots + roots.first()) }
        assertFailsWith<IllegalArgumentException> { RootActionKernelVector(listOf(1, 0), listOf(1.0, 1.0)) }
        assertFailsWith<IllegalArgumentException> { fitRootActionKernel(roots, actionWeights = mapOf("a" to listOf(1.0, 1.0))) }
        assertFailsWith<IllegalArgumentException> { roots.first().copy(actionMeans = listOf(Double.NaN, 1.0)) }
    }

    @Test fun `prediction needs features not labels study registration or producer identities`() {
        val directory = Files.createTempDirectory("research-predict-")
        try {
            val roots = listOf(root("a", 1.0), root("b", -1.0))
            writeJson(directory.resolve("model.json"), fitRootActionKernel(roots))
            writeJson(directory.resolve("input.json"), buildJsonArray { add(buildJsonObject {
                put("anythingTheResearcherNeeds", "ordinary metadata")
                put("features", researchJson.encodeToJsonElement(roots.first().features))
            }) })
            main(arrayOf("predict", directory.resolve("model.json").toString(),
                directory.resolve("input.json").toString(), directory.resolve("output.json").toString()))
            val row = readJson<JsonArray>(directory.resolve("output.json")).single().jsonObject
            assertEquals("ordinary metadata", row.getValue("anythingTheResearcherNeeds").jsonPrimitive.content)
            assertEquals(1, row.getValue("predictedIndex").jsonPrimitive.int)
            assertFalse("actionMeans" in row)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `kernel coordinates are not restricted to one experiment's feature dimensions`() {
        val feature = RootActionKernelFeatures(RootActionKernelVector(listOf(2000), listOf(1.0)),
            RootActionKernelVector(listOf(900), listOf(1.0)))
        assertEquals(2.0, rootActionKernel(feature, feature))
    }

    @Test fun `failed JSON publication preserves the previous file`() {
        val directory = Files.createTempDirectory("research-write-")
        try {
            val path = directory.resolve("data.json")
            writeJson(path, listOf(1.0))
            assertFails { writeJson(path, listOf(Double.NaN)) }
            assertEquals(listOf(1.0), readJson<List<Double>>(path))
            assertEquals(listOf(path), Files.list(directory).use { it.toList() })
        } finally { directory.toFile().deleteRecursively() }
    }
}
