package com.carecircle.push

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class NotificationDeliveryService(private val jdbc: JdbcTemplate) {
    fun list(requestId: UUID, actorId: UUID): NotificationDeliveriesResponse {
        val circleId = jdbc.query(
            "SELECT circle_id FROM urgent_request WHERE id = ?",
            RowMapper { rs, _ -> UUID.fromString(rs.getString("circle_id")) },
            requestId,
        ).firstOrNull() ?: throw PushSubscriptionNotFoundException()
        val activeMember = jdbc.query(
            "SELECT 1 FROM membership WHERE circle_id = ? AND user_id = ? AND removed_at IS NULL",
            RowMapper { _, _ -> true },
            circleId,
            actorId,
        ).isNotEmpty()
        if (!activeMember) throw PushSubscriptionForbiddenException()

        val deliveries = jdbc.query(
            "SELECT o.id, o.recipient_id, p.display_name, o.status, o.attempts, o.accepted_at " +
                "FROM notification_outbox o JOIN user_profile p ON p.user_id = o.recipient_id " +
                "WHERE o.request_id = ? ORDER BY p.display_name, o.created_at",
            RowMapper { rs, _ ->
                NotificationDeliveryResponse(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("recipient_id")),
                    rs.getString("display_name"),
                    NotificationDeliveryStatus.valueOf(rs.getString("status")),
                    rs.getInt("attempts"),
                    rs.getTimestamp("accepted_at")?.toInstant(),
                )
            },
            requestId,
        )
        return NotificationDeliveriesResponse(requestId, deliveries)
    }
}
