package org.mtgallium.agent.infoset.core

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.encodeToStream

const val POLICY_HISTORY_COMMITMENT_ALGORITHM: String = "sha256-chain-v1"

/** Incremental commitment to an exact prefix of a perspective-safe policy ledger. */
@Serializable
data class PolicyHistoryCommitment(
    val algorithm: String = POLICY_HISTORY_COMMITMENT_ALGORITHM,
    val cursor: Int,
    val digest: String,
) {
    @Transient
    private var decodedDigest: ByteArray? = null

    init {
        require(algorithm == POLICY_HISTORY_COMMITMENT_ALGORITHM) {
            "Unknown policy-history commitment algorithm $algorithm"
        }
        require(cursor >= 0) { "Policy-history cursor must be non-negative" }
        require(digest.length == 64 && digest.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Policy-history commitment must be a lowercase SHA-256"
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun append(event: PolicyHistoryEvent): PolicyHistoryCommitment {
        require(event.eventId == cursor.toLong()) {
            "Policy-history event ${event.eventId} is not contiguous at cursor $cursor"
        }
        val eventBytes = EVENT_BYTES_BY_THREAD.get().apply {
            reset()
            PolicyJson.format.encodeToStream(PolicyHistoryEvent.serializer(), event, this)
        }
        val hash = reusableSha256().apply {
            update(DOMAIN_BYTES)
            update(APPEND_TAG)
            update(digestBytes())
            updateLong(cursor.toLong() + 1L)
            updateLong(eventBytes.size().toLong())
            eventBytes.update(this)
        }.digest()
        return PolicyHistoryCommitment(cursor = cursor + 1, digest = hash.toLowerHex()).also {
            it.decodedDigest = hash
        }
    }

    private fun digestBytes(): ByteArray = decodedDigest ?: digest.lowerHexBytes().also { decodedDigest = it }

    companion object {
        private val DOMAIN_BYTES = "mtgallium:policy-history:sha256-chain-v1".toByteArray(StandardCharsets.UTF_8)
        private const val EMPTY_TAG: Byte = 0
        private const val APPEND_TAG: Byte = 1

        fun empty(): PolicyHistoryCommitment {
            val bytes = reusableSha256().apply {
                update(DOMAIN_BYTES)
                update(EMPTY_TAG)
            }.digest()
            return PolicyHistoryCommitment(cursor = 0, digest = bytes.toLowerHex()).also {
                it.decodedDigest = bytes
            }
        }

        fun replay(events: Iterable<PolicyHistoryEvent>): PolicyHistoryCommitment =
            events.fold(empty()) { commitment, event -> commitment.append(event) }

    }
}

private val EVENT_BYTES_BY_THREAD = ThreadLocal.withInitial { DigestByteArrayOutputStream() }

private class DigestByteArrayOutputStream : ByteArrayOutputStream(512) {
    fun update(digest: MessageDigest) = digest.update(buf, 0, count)
}

private fun MessageDigest.updateLong(value: Long) {
    for (shift in 56 downTo 0 step 8) update((value ushr shift).toByte())
}
