package network.disaster.management.security

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer

/**
 * Two-Person Integrity (TPI) Dual-Control Quorum Engine (Blueprint Section 14.D / DoD Directive 5210.41).
 * Requires dual-authorization by two distinct verified officers before high-consequence strategic
 * actions (e.g. panic database zeroization, audit truncation, master key wipe) can execute.
 * Self-authorization is algorithmically prohibited.
 */
object TwoPersonIntegrityManager {

    enum class StrategicAction(val actionCode: String) {
        PANIC_PHYSICAL_PURGE("ACTION_PANIC_PURGE"),
        AUDIT_TRAIL_TRUNCATE("ACTION_AUDIT_TRUNCATE"),
        MASTER_KEY_ZEROIZATION("ACTION_MASTER_KEY_ZEROIZATION")
    }

    data class OfficerEndorsement(
        val officerCallsign: String,
        val officerPublicKey: ByteArray,
        val action: StrategicAction,
        val expiryEpochMs: Long,
        val signature: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is OfficerEndorsement) return false
            return officerCallsign == other.officerCallsign &&
                    officerPublicKey.contentEquals(other.officerPublicKey) &&
                    action == other.action &&
                    expiryEpochMs == other.expiryEpochMs &&
                    signature.contentEquals(other.signature)
        }

        override fun hashCode(): Int {
            var result = officerCallsign.hashCode()
            result = 31 * result + officerPublicKey.contentHashCode()
            result = 31 * result + action.hashCode()
            result = 31 * result + expiryEpochMs.hashCode()
            result = 31 * result + signature.contentHashCode()
            return result
        }
    }

    class TpiQuorumViolationException(message: String) : SecurityException(message)

    /**
     * Builds the deterministic canonical payload buffer for signing.
     */
    fun createPayload(action: StrategicAction, expiryEpochMs: Long): ByteArray {
        val actionBytes = action.actionCode.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(8 + actionBytes.size)
        buf.putLong(expiryEpochMs)
        buf.put(actionBytes)
        return buf.array()
    }

    /**
     * Generates a signed endorsement slip for an officer.
     */
    fun signEndorsement(
        officerCallsign: String,
        privateKey: ByteArray,
        publicKey: ByteArray,
        action: StrategicAction,
        expiryEpochMs: Long = System.currentTimeMillis() + 300_000L // 5 min default
    ): OfficerEndorsement {
        val payload = createPayload(action, expiryEpochMs)
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        signer.update(payload, 0, payload.size)
        val sig = signer.generateSignature()

        return OfficerEndorsement(
            officerCallsign = officerCallsign,
            officerPublicKey = publicKey,
            action = action,
            expiryEpochMs = expiryEpochMs,
            signature = sig
        )
    }

    /**
     * Verifies the cryptographic signature of an officer endorsement slip.
     */
    fun verifyEndorsementSignature(endorsement: OfficerEndorsement): Boolean {
        if (System.currentTimeMillis() > endorsement.expiryEpochMs) {
            return false // Expired
        }
        return try {
            val payload = createPayload(endorsement.action, endorsement.expiryEpochMs)
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(endorsement.officerPublicKey, 0))
            verifier.update(payload, 0, payload.size)
            verifier.verifySignature(endorsement.signature)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Verifies that two distinct officers have properly authorized the strategic action.
     * Enforces:
     * 1. Both signatures must be cryptographically valid.
     * 2. Endorsements must not be expired.
     * 3. Endorsements must be for the exact same strategic action.
     * 4. Self-authorization is strictly blocked (officers must have different public keys and callsigns).
     */
    fun verifyDualControlQuorum(
        officerA: OfficerEndorsement,
        officerB: OfficerEndorsement,
        expectedAction: StrategicAction
    ): Boolean {
        // 1. Same action verification
        if (officerA.action != expectedAction || officerB.action != expectedAction) {
            throw TpiQuorumViolationException("TPI FAILURE: Endorsement action mismatch with requested action $expectedAction")
        }

        // 2. Algorithmic prevention of self-authorization
        if (officerA.officerPublicKey.contentEquals(officerB.officerPublicKey)) {
            throw TpiQuorumViolationException("TPI VIOLATION: Self-authorization blocked. Two distinct officer keys required.")
        }
        if (officerA.officerCallsign.equals(officerB.officerCallsign, ignoreCase = true)) {
            throw TpiQuorumViolationException("TPI VIOLATION: Duplicate officer callsign '${officerA.officerCallsign}'. Two distinct officers required.")
        }

        // 3. Expiration checks
        val now = System.currentTimeMillis()
        if (now > officerA.expiryEpochMs || now > officerB.expiryEpochMs) {
            throw TpiQuorumViolationException("TPI FAILURE: Endorsement authorization expired")
        }

        // 4. Cryptographic signature verification
        if (!verifyEndorsementSignature(officerA)) {
            throw TpiQuorumViolationException("TPI FAILURE: Officer A signature verification failed")
        }
        if (!verifyEndorsementSignature(officerB)) {
            throw TpiQuorumViolationException("TPI FAILURE: Officer B signature verification failed")
        }

        return true
    }
}
