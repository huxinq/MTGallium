package org.mtgallium.agent.argentum.policy

import kotlin.test.Test
import kotlin.test.assertEquals

class PolicyCompositionTest {
    @Test
    fun `production opponent and rollout defaults keep their recorded identities`() {
        assertEquals("mono-red-mixture-70-10-10-10-v2", defaultMonoRedOpponentPolicy().id)
        assertEquals("root-argentum-production-rollout-v2", PolicyDefaults.rootRolloutPolicy().id)
        assertEquals(
            "opponent-argentum-production-rollout-v2",
            PolicyDefaults.opponentRolloutPolicy().id,
        )
    }
}
