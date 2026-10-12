package network.disaster.management.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Citadel Defense Mode & DEFCON Emergency Enclave (Blueprint Section 14.E).
 * Under active attack detection (DEFCON 1), locks down the node into an emergency enclave:
 * 1. Restricts network ingress strictly to local loopback (127.0.0.1).
 * 2. Severs and rejects remote unauthenticated inbound sessions.
 * 3. Enforces tamper-proof read-only database enclave protection.
 */
object CitadelDefenseManager {

    enum class DefconLevel(val level: Int, val description: String) {
        DEFCON_5(5, "NORMAL_OPERATIONS"),
        DEFCON_3(3, "HEIGHTENED_THREAT_MONITORING"),
        DEFCON_1(1, "CITADEL_ENCLAVE_LOCKDOWN")
    }

    data class CitadelState(
        val defconLevel: DefconLevel = DefconLevel.DEFCON_5,
        val lockdownReason: String? = null,
        val engagedEpoch: Long = 0L,
        val isRemoteIngressBlocked: Boolean = false,
        val isReadOnlyEnclave: Boolean = false
    )

    private val _citadelState = MutableStateFlow(CitadelState())
    val citadelState: StateFlow<CitadelState> = _citadelState.asStateFlow()

    @Synchronized
    fun engageCitadelDefense(reason: String): CitadelState {
        val newState = CitadelState(
            defconLevel = DefconLevel.DEFCON_1,
            lockdownReason = reason,
            engagedEpoch = System.currentTimeMillis(),
            isRemoteIngressBlocked = true,
            isReadOnlyEnclave = true
        )
        _citadelState.value = newState
        return newState
    }

    @Synchronized
    fun elevateThreatLevel(level: DefconLevel, reason: String): CitadelState {
        val newState = CitadelState(
            defconLevel = level,
            lockdownReason = reason,
            engagedEpoch = System.currentTimeMillis(),
            isRemoteIngressBlocked = (level == DefconLevel.DEFCON_1),
            isReadOnlyEnclave = (level == DefconLevel.DEFCON_1)
        )
        _citadelState.value = newState
        return newState
    }

    @Synchronized
    fun disengageCitadelDefense(): CitadelState {
        val newState = CitadelState(
            defconLevel = DefconLevel.DEFCON_5,
            lockdownReason = null,
            engagedEpoch = 0L,
            isRemoteIngressBlocked = false,
            isReadOnlyEnclave = false
        )
        _citadelState.value = newState
        return newState
    }

    /**
     * Checks if remote ingress is allowed from client IP.
     * In DEFCON 1, only loopback (127.0.0.1 or ::1) is permitted.
     */
    fun isIngressPermitted(remoteIp: String): Boolean {
        val state = _citadelState.value
        if (!state.isRemoteIngressBlocked) return true
        val cleanIp = remoteIp.replace("^/+".toRegex(), "").replace("::ffff:", "").trim()
        return cleanIp == "127.0.0.1" || cleanIp == "::1" || cleanIp == "localhost"
    }

    fun isReadOnlyEnclave(): Boolean {
        return _citadelState.value.isReadOnlyEnclave
    }
}
