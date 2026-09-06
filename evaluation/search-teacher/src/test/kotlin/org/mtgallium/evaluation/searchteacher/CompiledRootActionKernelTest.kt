package org.mtgallium.evaluation.searchteacher

import kotlin.random.Random
import kotlin.test.*
import org.junit.jupiter.api.Tag

@Tag("public-source")
class CompiledRootActionKernelTest {
    private fun vector(vararg entries: Pair<Int, Double>) = RootActionKernelVector(entries.map { it.first }, entries.map { it.second })
    private fun features(state: RootActionKernelVector, candidate: RootActionKernelVector) = RootActionKernelFeatures(state, candidate)

    @Test fun `compiled bias and interaction preserve signs coordinates and unseen sparse queries`() {
        val centers = listOf(
            features(vector(0 to 2.0, 1023 to -3.0), vector(1 to 4.0, 511 to -2.0)),
            features(vector(4 to -1.0), vector(0 to 3.0, 511 to 2.0)),
        )
        val model = RootActionKernelModel(ridge = .001, centers = centers, coefficients = listOf(.5, -2.0))
        val compiled = CompiledRootActionKernel(model)
        for (state in listOf(vector(), vector(4 to 2.0, 1023 to -.5), vector(17 to 8.0))) {
            val menu = listOf(vector(0 to -1.0, 511 to 2.0), vector(1 to 3.0), vector(), vector(7 to 1.0))
                .map { features(state, it) }
            assertEquals(menu.map(model::score), compiled.scores(menu))
            assertEquals(compiled.scores(menu).reversed(), compiled.scores(menu.reversed()))
        }
        val frozen = compiled.scores(centers.take(1))
        compiled.scores(listOf(features(vector(), vector())))
        assertEquals(frozen, compiled.scores(centers.take(1)))
    }

    @Test fun `finite mixed signed centers agree numerically with dual scoring and preserve separated argmax`() {
        val random = Random(81821)
        fun sparse(dimension: Int) = (List(12) { random.nextInt(dimension) }).distinct().sorted().let { indices ->
            RootActionKernelVector(indices, indices.map { random.nextDouble(-1.0, 1.0) })
        }
        val model = RootActionKernelModel(ridge = .001,
            centers = List(83) { features(sparse(1024), sparse(512)) }, coefficients = List(83) { random.nextDouble(-3.0, 3.0) })
        val serialized = evidenceJson.encodeToString(RootActionKernelModel.serializer(), model)
        val compiled = CompiledRootActionKernel(model)
        repeat(30) {
            val state = sparse(1024)
            val menu = List(7) { features(state, sparse(512)) }
            val dual = menu.map(model::score)
            val primal = compiled.scores(menu)
            dual.indices.forEach { assertEquals(dual[it], primal[it], 1e-12) }
            assertEquals(dual.indices.maxBy { dual[it] }, primal.indices.maxBy { primal[it] })
        }
        assertEquals(serialized, evidenceJson.encodeToString(RootActionKernelModel.serializer(), model))
    }

    @Test fun `shared state shape and finite arithmetic are enforced without silent score clipping`() {
        val f = features(vector(0 to 1.0), vector(0 to 1.0))
        val model = RootActionKernelModel(ridge = .001, centers = listOf(f), coefficients = listOf(1.0))
        val scorer = CompiledRootActionKernel(model)
        assertEquals(listOf(2.0), scorer.scores(listOf(f)))
        assertFailsWith<IllegalArgumentException> { scorer.scores(emptyList()) }
        assertFailsWith<IllegalArgumentException> { scorer.scores(listOf(f, f.copy(state = vector(1 to 1.0)))) }
        assertFailsWith<IllegalArgumentException> { scorer.scores(listOf(f.copy(state = vector(1024 to 1.0)))) }
        assertFailsWith<IllegalArgumentException> { scorer.scores(listOf(f.copy(centeredCandidate = vector(512 to 1.0)))) }
        assertFailsWith<IllegalArgumentException> {
            CompiledRootActionKernel(model.copy(centers = listOf(f.copy(state = vector(0 to Double.MAX_VALUE))), coefficients = listOf(2.0)))
        }
        assertFailsWith<IllegalArgumentException> {
            scorer.scores(listOf(f.copy(state = vector(0 to Double.MAX_VALUE), centeredCandidate = vector(0 to 2.0))))
        }
    }
}
