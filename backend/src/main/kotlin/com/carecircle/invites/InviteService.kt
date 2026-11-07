package com.carecircle.invites

import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Service
class InviteService(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    @param:Value("\${care-circle.frontend-base-url:http://localhost:5173}") private val frontendBaseUrl: String,
) {
    private val transactions = TransactionTemplate(transactionManager)

    fun create(circleId: UUID, actorId: UUID, idempotencyKey: String, request: CreateInviteRequest): InviteResponse {
        validateIdempotencyKey(idempotencyKey)
        validateToken(request.token)
        val tokenHash = sha256(request.token)
        val requestHash = sha256("${request.id}\n${request.role}\n$tokenHash")

        try {
            return transactions.execute {
                requireOwner(circleId, actorId)
                val existing = findForIdempotency(circleId, actorId, idempotencyKey)
                if (existing != null) return@execute replay(existing, requestHash, request.token)

                val now = Instant.now()
                val expiresAt = now.plus(INVITE_TTL)
                jdbc.update(
                    "INSERT INTO circle_invite " +
                        "(id, circle_id, token_hash, created_by, role, idempotency_key, request_hash, created_at, expires_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    request.id, circleId, tokenHash, actorId, request.role.name, idempotencyKey, requestHash,
                    Timestamp.from(now), Timestamp.from(expiresAt),
                )
                jdbc.update("UPDATE circle SET change_seq = change_seq + 1 WHERE id = ?", circleId)
                InviteResponse(request.id, circleId, request.role, inviteUrl(request.token), expiresAt)
            } ?: error("Invite transaction returned no result")
        } catch (_: DuplicateKeyException) {
            // Return the same link after a concurrent retry, but only if the stored request matches exactly.
            requireOwner(circleId, actorId)
            val existing = findForIdempotency(circleId, actorId, idempotencyKey)
                ?: throw InviteConflictException("Invite ID or token is already in use")
            return replay(existing, requestHash, request.token)
        }
    }

    fun preview(token: String): InvitePreviewResponse {
        val invite = findByToken(token) ?: throw InviteNotFoundException()
        ensureUsable(invite)
        return InvitePreviewResponse(invite.circleId, invite.circleName, invite.role, invite.expiresAt)
    }

    fun accept(
        token: String,
        actorId: UUID,
        idempotencyKey: String,
        request: AcceptInviteRequest,
    ): InviteAcceptanceResponse {
        validateIdempotencyKey(idempotencyKey)
        validateToken(token)
        val displayName = request.displayName.trim()
        if (displayName.isEmpty() || displayName.length > 80) {
            throw InviteInvalidRequestException("Your name is required")
        }
        val tokenHash = sha256(token)

        try {
            return transactions.execute {
                val invite = jdbc.query(
                    "SELECT i.id, i.circle_id, i.role, i.expires_at, i.accepted_at, i.accepted_by, c.name AS circle_name " +
                        "FROM circle_invite i JOIN circle c ON c.id = i.circle_id " +
                        "WHERE i.token_hash = ? FOR UPDATE",
                    inviteRowMapper,
                    tokenHash,
                ).firstOrNull() ?: throw InviteNotFoundException()

                val acceptedAt = invite.acceptedAt
                if (acceptedAt != null) {
                    if (invite.acceptedBy != actorId) {
                        throw InviteConflictException("This invite has already been used")
                    }
                    return@execute invite.toAcceptance(acceptedAt, alreadyAccepted = true)
                }
                ensureUsable(invite)

                val existingMembership = jdbc.query(
                    "SELECT removed_at FROM membership WHERE circle_id = ? AND user_id = ? FOR UPDATE",
                    RowMapper { rs, _ -> ExistingMembership(rs.getTimestamp("removed_at")?.toInstant()) },
                    invite.circleId,
                    actorId,
                ).firstOrNull()
                if (existingMembership != null && existingMembership.removedAt == null) {
                    throw InviteConflictException("You are already a member of this circle")
                }

                val acceptedInstant = Instant.now()
                upsertProfile(actorId, displayName)
                if (existingMembership == null) {
                    jdbc.update(
                        "INSERT INTO membership (circle_id, user_id, role) VALUES (?, ?, ?)",
                        invite.circleId,
                        actorId,
                        invite.role.name,
                    )
                } else {
                    jdbc.update(
                        "UPDATE membership SET role = ?, joined_at = ?, removed_at = NULL, removed_by = NULL " +
                            "WHERE circle_id = ? AND user_id = ? AND removed_at IS NOT NULL",
                        invite.role.name,
                        Timestamp.from(acceptedInstant),
                        invite.circleId,
                        actorId,
                    )
                }
                jdbc.update(
                    "UPDATE circle_invite SET accepted_at = ?, accepted_by = ? WHERE id = ? AND accepted_at IS NULL",
                    Timestamp.from(acceptedInstant),
                    actorId,
                    invite.id,
                )
                jdbc.update("UPDATE circle SET change_seq = change_seq + 1 WHERE id = ?", invite.circleId)
                invite.toAcceptance(acceptedInstant, alreadyAccepted = false)
            } ?: error("Invite acceptance transaction returned no result")
        } catch (_: DuplicateKeyException) {
            // Protect against concurrent acceptance through different invitations for an existing membership.
            throw InviteConflictException("You are already a member of this circle")
        }
    }

    private fun requireOwner(circleId: UUID, actorId: UUID) {
        val role = jdbc.query(
            "SELECT role FROM membership WHERE circle_id = ? AND user_id = ? AND removed_at IS NULL",
            RowMapper { rs, _ -> rs.getString("role") },
            circleId,
            actorId,
        ).firstOrNull() ?: throw InviteForbiddenException()
        if (role != "OWNER") throw InviteForbiddenException()
    }

    private fun findForIdempotency(circleId: UUID, actorId: UUID, key: String): StoredInvite? = jdbc.query(
        "SELECT i.id, i.circle_id, i.role, i.request_hash, i.expires_at " +
            "FROM circle_invite i WHERE i.circle_id = ? AND i.created_by = ? AND i.idempotency_key = ?",
        RowMapper { rs, _ ->
            StoredInvite(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("circle_id")),
                InviteRole.valueOf(rs.getString("role")),
                rs.getString("request_hash"),
                rs.getTimestamp("expires_at").toInstant(),
            )
        },
        circleId,
        actorId,
        key,
    ).firstOrNull()

    private fun findByToken(token: String): StoredPreview? {
        if (!isValidToken(token)) return null
        return jdbc.query(
            "SELECT i.id, i.circle_id, i.role, i.expires_at, i.accepted_at, i.accepted_by, " +
                "c.name AS circle_name FROM circle_invite i JOIN circle c ON c.id = i.circle_id " +
                "WHERE i.token_hash = ?",
            inviteRowMapper,
            sha256(token),
        ).firstOrNull()
    }

    private fun replay(existing: StoredInvite, requestHash: String, token: String): InviteResponse {
        if (existing.requestHash != requestHash) {
            throw InviteConflictException("Idempotency-Key was already used with a different invite")
        }
        return InviteResponse(existing.id, existing.circleId, existing.role, inviteUrl(token), existing.expiresAt)
    }

    private fun ensureUsable(invite: StoredPreview) {
        if (invite.acceptedAt != null) throw InviteConflictException("This invite has already been used")
        if (!invite.expiresAt.isAfter(Instant.now())) throw InviteExpiredException()
    }

    private fun inviteUrl(token: String): String = "${frontendBaseUrl.trimEnd('/')}/invites/$token"

    private fun upsertProfile(userId: UUID, displayName: String) {
        val updated = jdbc.update(
            "UPDATE user_profile SET display_name = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?",
            displayName,
            userId,
        )
        if (updated == 0) {
            jdbc.update("INSERT INTO user_profile (user_id, display_name) VALUES (?, ?)", userId, displayName)
        }
    }

    private fun validateIdempotencyKey(key: String) {
        if (key.isBlank() || key.length > 128) {
            throw InviteInvalidRequestException("Idempotency-Key must be 1-128 characters")
        }
    }

    private fun validateToken(token: String) {
        if (!isValidToken(token)) throw InviteNotFoundException()
    }

    private fun isValidToken(token: String): Boolean = runCatching {
        token.length == TOKEN_LENGTH && Base64.getUrlDecoder().decode(token).size == TOKEN_BYTES
    }.getOrDefault(false)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun StoredPreview.toAcceptance(acceptedAt: Instant, alreadyAccepted: Boolean) =
        InviteAcceptanceResponse(circleId, circleName, role, acceptedAt, alreadyAccepted)

    private data class StoredInvite(
        val id: UUID,
        val circleId: UUID,
        val role: InviteRole,
        val requestHash: String,
        val expiresAt: Instant,
    )

    private data class ExistingMembership(val removedAt: Instant?)

    private data class StoredPreview(
        val id: UUID,
        val circleId: UUID,
        val role: InviteRole,
        val expiresAt: Instant,
        val acceptedAt: Instant?,
        val acceptedBy: UUID?,
        val circleName: String,
    )

    private val inviteRowMapper = RowMapper { rs, _ ->
        StoredPreview(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("circle_id")),
            InviteRole.valueOf(rs.getString("role")),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("accepted_at")?.toInstant(),
            rs.getString("accepted_by")?.let(UUID::fromString),
            rs.getString("circle_name"),
        )
    }

    private companion object {
        val INVITE_TTL: Duration = Duration.ofDays(7)
        const val TOKEN_BYTES = 32
        const val TOKEN_LENGTH = 43
    }
}
