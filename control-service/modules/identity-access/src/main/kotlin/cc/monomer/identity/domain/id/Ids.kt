package cc.monomer.identity.domain.id

import kotlin.uuid.Uuid

/**
 * UUID v7 value-class IDs for auth-service.
 *
 * - Backed by `kotlin.uuid.Uuid` (Kotlin 2.3 stable).
 * - `generate()` factory mints UUIDv7 (time-ordered) via `Uuid.generateV7()` — matches backend-conventions.
 * - Postgres column type is native `UUID`; mappers convert via `toJavaUuid()` /
 *   `Uuid.fromJavaUuid()` at the adapter boundary.
 */

@JvmInline
value class OrganizationId(val value: Uuid) {
    companion object {
        fun generate(): OrganizationId = OrganizationId(Uuid.generateV7())
    }
    override fun toString(): String = value.toString()
}

/**
 * For a user, principalId == userId.
 */
@JvmInline
value class PrincipalId(val value: Uuid) {
    companion object {
        fun generate(): PrincipalId = PrincipalId(Uuid.generateV7())
    }
    override fun toString(): String = value.toString()
}

@JvmInline
value class UserId(val value: Uuid) {
    companion object {
        fun generate(): UserId = UserId(Uuid.generateV7())
    }
    fun asPrincipal(): PrincipalId = PrincipalId(value)
    override fun toString(): String = value.toString()
}

@JvmInline
value class WorkspaceId(val value: Uuid) {
    companion object {
        fun generate(): WorkspaceId = WorkspaceId(Uuid.generateV7())
    }
    override fun toString(): String = value.toString()
}

@JvmInline
value class RefreshTokenId(val value: Uuid) {
    companion object {
        fun generate(): RefreshTokenId = RefreshTokenId(Uuid.generateV7())
    }
    override fun toString(): String = value.toString()
}

@JvmInline
value class LoginIdentityId(val value: Uuid) {
    companion object {
        fun generate(): LoginIdentityId = LoginIdentityId(Uuid.generateV7())
    }
    override fun toString(): String = value.toString()
}

@JvmInline
value class SessionId(val value: Uuid) {
    companion object {
        fun generate(): SessionId = SessionId(Uuid.generateV7())
    }
    override fun toString(): String = value.toString()
}
