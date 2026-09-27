package org.mtgallium.agent.infoset.argentum

/** Size-[size] subsets of [values] in index-lexicographic order; none outside 0..values.size. */
internal fun <T> kSubsets(values: List<T>, size: Int): Sequence<List<T>> = sequence {
    suspend fun SequenceScope<List<T>>.visit(start: Int, remaining: Int, prefix: MutableList<T>) {
        if (remaining == 0) {
            yield(prefix.toList())
            return
        }
        for (index in start..values.size - remaining) {
            prefix += values[index]
            visit(index + 1, remaining - 1, prefix)
            prefix.removeAt(prefix.lastIndex)
        }
    }
    if (size in 0..values.size) visit(0, size, mutableListOf())
}

/** Ordered size-[size] selections of distinct positions, in index-lexicographic order. */
internal fun <T> kPermutations(values: List<T>, size: Int): Sequence<List<T>> = sequence {
    suspend fun SequenceScope<List<T>>.visit(prefix: MutableList<T>, remaining: MutableList<T>) {
        if (prefix.size == size) {
            yield(prefix.toList())
            return
        }
        for (index in remaining.indices.toList()) {
            val value = remaining.removeAt(index)
            prefix += value
            visit(prefix, remaining)
            prefix.removeAt(prefix.lastIndex)
            remaining.add(index, value)
        }
    }
    if (size in 0..values.size) visit(mutableListOf(), values.toMutableList())
}

/** Cartesian product with the first dimension varying slowest; empty when any dimension is. */
internal fun <T> cartesianProduct(dimensions: List<List<T>>): Sequence<List<T>> = sequence {
    suspend fun SequenceScope<List<T>>.visit(index: Int, prefix: MutableList<T>) {
        if (index == dimensions.size) {
            yield(prefix.toList())
            return
        }
        for (value in dimensions[index]) {
            prefix += value
            visit(index + 1, prefix)
            prefix.removeAt(prefix.lastIndex)
        }
    }
    visit(0, mutableListOf())
}
