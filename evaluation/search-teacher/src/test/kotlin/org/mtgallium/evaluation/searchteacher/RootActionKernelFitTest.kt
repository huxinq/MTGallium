package org.mtgallium.evaluation.searchteacher

import kotlin.test.*
import org.junit.jupiter.api.Tag

@Tag("public-source")
class RootActionKernelFitTest {
    private fun vector(value: Double) = RootActionKernelVector(listOf(0), listOf(value))
    private fun features(state: Double, action: Double) = RootActionKernelFeatures(vector(state), vector(action))
    private fun root(id: String, state: Double, offset: Double = 0.0) = RootActionKernelTrainingRoot(id, id,
        listOf(features(state, -1.0), features(state, 1.0)), listOf(-state * .3 + offset, state * .3 + offset))

    @Test fun `interaction kernel learns opposite preferred actions in different states`() {
        val roots = listOf(root("positive", 1.0), root("negative", -1.0))
        val model = fitRootActionKernel(roots, 1e-6)
        assertTrue(model.score(features(.5, 1.0)) > model.score(features(.5, -1.0)))
        assertTrue(model.score(features(-.5, -1.0)) > model.score(features(-.5, 1.0)))
        assertEquals(.15, model.score(features(.5, 1.0)), 1e-5)
        val restored = evidenceJson.decodeFromString<RootActionKernelModel>(evidenceJson.encodeToString(RootActionKernelModel.serializer(), model))
        assertEquals(model, restored)
        assertEquals(model.score(features(.5, 1.0)), restored.score(features(.5, 1.0)))
    }
    @Test fun `root offsets do not become action preference and replicated group rows do not increase influence`() {
        val roots = listOf(root("positive", 1.0), root("negative", -1.0))
        val model = fitRootActionKernel(roots, .01)
        val shifted = fitRootActionKernel(listOf(root("positive", 1.0, .2), root("negative", -1.0, -.2)), .01)
        val duplicate = roots.first().copy(rootId = "positive-copy")
        val replicated = fitRootActionKernel(roots + duplicate, .01)
        for (state in listOf(-1.0, .3, 1.0)) for (action in listOf(-1.0, 1.0)) {
            val f = features(state, action)
            assertEquals(model.score(f), shifted.score(f), 1e-12)
            assertEquals(model.score(f), replicated.score(f), 1e-12)
        }
    }
    @Test fun `regularized solve handles singular features and rejects invalid input`() {
        val roots = listOf(root("a", 0.0), root("b", 0.0))
        val model = fitRootActionKernel(roots, .001)
        assertEquals(0.0, model.score(features(1.0, 1.0)))
        val matrix = arrayOf(doubleArrayOf(4.0, 1.0), doubleArrayOf(1.0, 3.0))
        val x = solveRootActionKernel(matrix, doubleArrayOf(1.0, 2.0))
        assertEquals(1.0, 4*x[0]+x[1], 1e-12); assertEquals(2.0, x[0]+3*x[1], 1e-12)
        assertFailsWith<IllegalArgumentException> { fitRootActionKernel(roots, 0.0) }
        assertFailsWith<IllegalArgumentException> { fitRootActionKernel(roots + roots.first(), .1) }
        assertFailsWith<IllegalArgumentException> { RootActionKernelVector(listOf(1, 0), listOf(1.0, 1.0)) }
        assertFailsWith<IllegalArgumentException> { solveRootActionKernel(arrayOf(doubleArrayOf(-1.0)), doubleArrayOf(1.0)) }
    }
}
