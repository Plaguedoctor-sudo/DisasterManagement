package network.disaster.management.security

/**
 * Multi-Level Security (MLS) & Bell-LaPadula Simple Security Property (Blueprint Section 6 & 14.C / DoD 5200.01).
 * Enforces mandatory access controls (MAC) across military-grade classification boundaries:
 * 1. Simple Security Property ("No Read Up"): Subject cannot inspect records classified above clearance.
 * 2. Star Property (*-Property / "No Write Down"): Subject cannot write sensitive data down to a lower classification channel.
 */
object MultiLevelSecurityPolicy {

    enum class SecurityClassification(val rank: Int, val designation: String) {
        UNCLASSIFIED(1, "UNCLASSIFIED"),         // Front desk triage queues, public broadcasts, basic civilian registration
        CONFIDENTIAL(2, "CONFIDENTIAL"),         // Field logistics, eMAR telemetry, internal relief manifests
        SECRET(3, "SECRET"),                     // Protected Health Information (PHI), casualty triage, prescription seals
        TOP_SECRET(4, "TOP_SECRET")              // Master cryptographic keys, root audit ledger, DEFCON panic switches
    }

    enum class SystemRole(val defaultClearance: SecurityClassification, val roleName: String) {
        CIVILIAN(SecurityClassification.UNCLASSIFIED, "CIVILIAN"),
        FIRST_RESPONDER(SecurityClassification.CONFIDENTIAL, "FIRST_RESPONDER"),
        DOCTOR_CLINICIAN(SecurityClassification.SECRET, "DOCTOR_CLINICIAN"),
        ADMIN_OFFICER(SecurityClassification.TOP_SECRET, "ADMIN_OFFICER")
    }

    data class SubjectCredentials(
        val actorId: String,
        val role: SystemRole,
        val clearance: SecurityClassification = role.defaultClearance
    )

    class AccessDeniedSecurityException(message: String) : SecurityException(message)

    /**
     * Bell-LaPadula Simple Security Property: "No Read Up".
     * A subject with clearance S cannot read an object classified at level O unless S >= O.
     */
    fun isReadPermitted(subject: SubjectCredentials, objectClassification: SecurityClassification): Boolean {
        return subject.clearance.rank >= objectClassification.rank
    }

    /**
     * Bell-LaPadula Star Property (*-Property): "No Write Down".
     * A subject with clearance S cannot write/leak information to a channel classified at O if S > O.
     */
    fun isWritePermitted(subject: SubjectCredentials, targetChannelClassification: SecurityClassification): Boolean {
        return subject.clearance.rank <= targetChannelClassification.rank
    }

    /**
     * Asserts that a subject has authority to read an object, throwing an exception if unauthorized.
     */
    fun assertReadAllowed(subject: SubjectCredentials, objectClassification: SecurityClassification, resourceName: String) {
        if (!isReadPermitted(subject, objectClassification)) {
            throw AccessDeniedSecurityException(
                "MLS VIOLATION (No-Read-Up): Subject '${subject.actorId}' (${subject.clearance}) " +
                        "denied read access to '$resourceName' ($objectClassification)"
            )
        }
    }

    /**
     * Asserts that a subject is permitted to write to a target channel, throwing an exception if unauthorized.
     */
    fun assertWriteAllowed(subject: SubjectCredentials, targetChannelClassification: SecurityClassification, resourceName: String) {
        if (!isWritePermitted(subject, targetChannelClassification)) {
            throw AccessDeniedSecurityException(
                "MLS VIOLATION (No-Write-Down): Subject '${subject.actorId}' (${subject.clearance}) " +
                        "denied write access down to '$resourceName' ($targetChannelClassification)"
            )
        }
    }

    /**
     * Role-Based Access Control Server Guard (Blueprint Section 6.1).
     */
    fun requireRole(subject: SubjectCredentials, allowedRoles: Set<SystemRole>, operation: String) {
        if (!allowedRoles.contains(subject.role)) {
            throw AccessDeniedSecurityException(
                "RBAC VIOLATION: Role '${subject.role.roleName}' lacks authorization for '$operation'. " +
                        "Required: ${allowedRoles.map { it.roleName }}"
            )
        }
    }

    /**
     * Declarative Permissions Matrix (Blueprint Section 4.1 & Step 6).
     */
    enum class Permission(val permissionKey: String) {
        ALERT_BROADCAST("alert:broadcast"),
        SOS_TRIGGER("sos:trigger"),
        CASUALTY_TRIAGE_WRITE("casualty:triage:write"),
        CASUALTY_TRIAGE_VIEW("casualty:triage:view"),
        FIELD_LOGISTICS_MANAGE("logistics:manage"),
        SECURITY_AUDIT_VIEW("security:audit:view"),
        DEFCON_LOCKDOWN_CONTROL("security:defcon:control"),
        VAULT_PURGE_EXECUTE("security:purge:execute")
    }

    val ROLE_PERMISSIONS: Map<SystemRole, Set<Permission>> = mapOf(
        SystemRole.CIVILIAN to setOf(
            Permission.ALERT_BROADCAST,
            Permission.SOS_TRIGGER
        ),
        SystemRole.FIRST_RESPONDER to setOf(
            Permission.ALERT_BROADCAST,
            Permission.SOS_TRIGGER,
            Permission.CASUALTY_TRIAGE_VIEW,
            Permission.FIELD_LOGISTICS_MANAGE
        ),
        SystemRole.DOCTOR_CLINICIAN to setOf(
            Permission.ALERT_BROADCAST,
            Permission.SOS_TRIGGER,
            Permission.CASUALTY_TRIAGE_VIEW,
            Permission.CASUALTY_TRIAGE_WRITE,
            Permission.FIELD_LOGISTICS_MANAGE
        ),
        SystemRole.ADMIN_OFFICER to Permission.values().toSet()
    )

    fun canDo(role: SystemRole, permission: Permission): Boolean {
        if (role == SystemRole.ADMIN_OFFICER) return true
        return ROLE_PERMISSIONS[role]?.contains(permission) ?: false
    }

    fun requirePermission(subject: SubjectCredentials, permission: Permission) {
        if (!canDo(subject.role, permission)) {
            throw AccessDeniedSecurityException(
                "RBAC VIOLATION: Role '${subject.role.roleName}' lacks permission '${permission.permissionKey}'"
            )
        }
    }
}
