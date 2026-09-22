package org.mtgallium.agent.infoset.core

/**
 * Trusted descendant check for information actually observed after a transition.
 * The adapter supplies the complete represented-information/knowledge predicate;
 * this is not an opponent-policy input or access to an unobserved response.
 */
class ParticleObservationCondition(
    val knowledgeDigest: String?,
    private val acceptsWorld: (SearchWorld) -> Boolean,
) {
    fun matches(world: SearchWorld): Boolean = acceptsWorld(world)
}
