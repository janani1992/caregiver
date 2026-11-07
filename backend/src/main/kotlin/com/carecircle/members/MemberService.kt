package com.carecircle.members

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Service
class MemberService(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    fun list(circleId: UUID, actorId: UUID): CircleMembersResponse {
        val currentRole = activeRole(circleId, actorId) ?: throw MemberForbiddenException()
        val details = jdbc.query(
            "SELECT c.name AS circle_name, m.user_id, p.display_name, m.role, m.joined_at " +
                "FROM circle c " +
                "JOIN membership m ON m.circle_id = c.id AND m.removed_at IS NULL " +
                "JOIN user_profile p ON p.user_id = m.user_id " +
                "WHERE c.id = ? ORDER BY m.joined_at, p.display_name",
            RowMapper { rs, _ ->
                MemberSummary(
                    UUID.fromString(rs.getString("user_id")),
                    rs.getString("display_name"),
                    MemberRole.valueOf(rs.getString("role")),
                    rs.getTimestamp("joined_at").toInstant(),
                ) to rs.getString("circle_name")
            },
            circleId,
        )
        val circleName = details.firstOrNull()?.second ?: throw MemberForbiddenException()
        return CircleMembersResponse(circleId, circleName, currentRole, details.map { it.first })
    }

    fun remove(circleId: UUID, actorId: UUID, targetId: UUID, idempotencyKey: String) {
        validateIdempotencyKey(idempotencyKey)
        transactions.executeWithoutResult {
            if (activeRole(circleId, actorId) != MemberRole.OWNER) throw MemberForbiddenException()
            if (actorId == targetId) throw MemberConflictException("Owners cannot remove themselves")

            val target = jdbc.query(
                "SELECT role, removed_at FROM membership WHERE circle_id = ? AND user_id = ? FOR UPDATE",
                RowMapper { rs, _ ->
                    targetRow(
                        MemberRole.valueOf(rs.getString("role")),
                        rs.getTimestamp("removed_at")?.toInstant(),
                    )
                },
                circleId,
                targetId,
            ).firstOrNull() ?: throw MemberNotFoundException()

            if (target.role == MemberRole.OWNER) {
                throw MemberConflictException("An owner cannot be removed")
            }
            // Treat a repeat delete as success without adding duplicate audit events or sequence changes.
            if (target.removedAt != null) return@executeWithoutResult

            val removedAt = Instant.now()
            jdbc.update(
                "UPDATE membership SET removed_at = ?, removed_by = ? WHERE circle_id = ? AND user_id = ? AND removed_at IS NULL",
                Timestamp.from(removedAt),
                actorId,
                circleId,
                targetId,
            )
            jdbc.update(
                "INSERT INTO membership_removal (circle_id, user_id, removed_by, removed_at) VALUES (?, ?, ?, ?)",
                circleId,
                targetId,
                actorId,
                Timestamp.from(removedAt),
            )
            jdbc.update("UPDATE circle SET change_seq = change_seq + 1 WHERE id = ?", circleId)
        }
    }

    private fun activeRole(circleId: UUID, userId: UUID): MemberRole? = jdbc.query(
        "SELECT role FROM membership WHERE circle_id = ? AND user_id = ? AND removed_at IS NULL",
        RowMapper { rs, _ -> MemberRole.valueOf(rs.getString("role")) },
        circleId,
        userId,
    ).firstOrNull()

    private fun validateIdempotencyKey(key: String) {
        if (key.isBlank() || key.length > 128) {
            throw MemberInvalidRequestException("Idempotency-Key must be 1-128 characters")
        }
    }

    private data class TargetRow(val role: MemberRole, val removedAt: Instant?)

    private fun targetRow(role: MemberRole, removedAt: Instant?) = TargetRow(role, removedAt)
}
