package com.carecircle.push

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Component
class NotificationOutboxDispatcher(
    private val jdbc: JdbcTemplate,
    private val pushProvider: PushProvider,
    private val secretCipher: PushSecretCipher,
    transactionManager: PlatformTransactionManager,
    @param:Value("\${care-circle.push.max-attempts:5}") private val maxAttempts: Int,
) {
    private val transactions = TransactionTemplate(transactionManager)

    @Scheduled(fixedDelayString = "\${care-circle.push.dispatch-delay-ms:5000}")
    fun dispatchPending() {
        repeat(BATCH_SIZE) {
            if (!dispatchOne()) return
        }
    }

    fun dispatchOne(): Boolean {
        val entry = claimNext() ?: return false
        val target = loadActiveTarget(entry)
        if (target == null) {
            finish(entry.id, NotificationDeliveryStatus.CANCELLED, entry.attempts, null)
            return true
        }

        return try {
            when (pushProvider.send(target, notificationFor(entry))) {
                PushSendResult.ACCEPTED -> finish(
                    entry.id,
                    NotificationDeliveryStatus.ACCEPTED,
                    entry.attempts + 1,
                    null,
                )
                PushSendResult.SIMULATED -> finish(
                    entry.id,
                    NotificationDeliveryStatus.SIMULATED,
                    entry.attempts + 1,
                    null,
                )
                PushSendResult.SUBSCRIPTION_GONE -> {
                    disableSubscription(entry.subscriptionId)
                    finish(entry.id, NotificationDeliveryStatus.CANCELLED, entry.attempts + 1, "SUBSCRIPTION_GONE")
                }
            }
            true
        } catch (exception: PushDeliveryException) {
            val attempts = entry.attempts + 1
            val status = if (exception.retryable && attempts < maxAttempts.coerceAtLeast(1)) {
                NotificationDeliveryStatus.PENDING
            } else {
                NotificationDeliveryStatus.FAILED
            }
            finish(entry.id, status, attempts, exception.errorCode)
            true
        } catch (exception: Exception) {
            val attempts = entry.attempts + 1
            val status = if (attempts >= maxAttempts.coerceAtLeast(1)) {
                NotificationDeliveryStatus.FAILED
            } else {
                NotificationDeliveryStatus.PENDING
            }
            finish(entry.id, status, attempts, exception.javaClass.simpleName.take(80))
            true
        }
    }

    private fun claimNext(): OutboxEntry? = transactions.execute {
        val now = Instant.now()
        val entry = jdbc.query(
            "SELECT id, circle_id, request_id, recipient_id, subscription_id, attempts " +
                "FROM notification_outbox " +
                "WHERE (status = 'PENDING' AND next_attempt_at <= ?) " +
                "OR (status = 'PROCESSING' AND locked_at < ?) " +
                "ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED",
            outboxRowMapper,
            Timestamp.from(now),
            Timestamp.from(now.minusSeconds(LEASE_SECONDS)),
        ).firstOrNull() ?: return@execute null
        jdbc.update(
            "UPDATE notification_outbox SET status = 'PROCESSING', locked_at = ?, updated_at = ? WHERE id = ?",
            Timestamp.from(now),
            Timestamp.from(now),
            entry.id,
        )
        entry
    }

    private fun loadActiveTarget(entry: OutboxEntry): PushTarget? = jdbc.query(
        "SELECT ps.id, ps.endpoint_ciphertext, ps.p256dh_key, ps.auth_secret " +
            "FROM push_subscription ps " +
            "JOIN membership m ON m.user_id = ps.user_id AND m.circle_id = ? AND m.removed_at IS NULL " +
            "JOIN urgent_request r ON r.id = ? AND r.circle_id = m.circle_id AND r.state = 'OPEN' " +
            "WHERE ps.id = ? AND ps.user_id = ? AND ps.disabled_at IS NULL AND m.role IN ('OWNER', 'FAMILY', 'HELPER')",
        RowMapper { rs, _ ->
            PushTarget(
                UUID.fromString(rs.getString("id")),
                secretCipher.decrypt(rs.getString("endpoint_ciphertext")),
                secretCipher.decrypt(rs.getString("p256dh_key")),
                secretCipher.decrypt(rs.getString("auth_secret")),
            )
        },
        entry.circleId,
        entry.requestId,
        entry.subscriptionId,
        entry.recipientId,
    ).firstOrNull()

    private fun notificationFor(entry: OutboxEntry) = PushNotification(
        entry.id,
        "Care Circle",
        "A new urgent request needs attention.",
        "/circles/${entry.circleId}?urgentRequestId=${entry.requestId}",
    )

    private fun finish(
        id: UUID,
        status: NotificationDeliveryStatus,
        attempts: Int,
        errorCode: String?,
    ) {
        val now = Instant.now()
        val retryAt = if (status == NotificationDeliveryStatus.PENDING) {
            now.plusSeconds(RETRY_BASE_SECONDS * (1L shl (attempts - 1).coerceIn(0, 10)))
        } else {
            now
        }
        jdbc.update(
            "UPDATE notification_outbox SET status = ?, attempts = ?, next_attempt_at = ?, locked_at = NULL, " +
                "accepted_at = ?, last_error_code = ?, updated_at = ? WHERE id = ?",
            status.name,
            attempts,
            Timestamp.from(retryAt),
            if (status == NotificationDeliveryStatus.ACCEPTED) Timestamp.from(now) else null,
            errorCode,
            Timestamp.from(now),
            id,
        )
    }

    private fun disableSubscription(subscriptionId: UUID) {
        val now = Timestamp.from(Instant.now())
        jdbc.update(
            "UPDATE push_subscription SET disabled_at = ?, updated_at = ? WHERE id = ? AND disabled_at IS NULL",
            now,
            now,
            subscriptionId,
        )
    }

    private val outboxRowMapper = RowMapper { rs, _ ->
        OutboxEntry(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("circle_id")),
            UUID.fromString(rs.getString("request_id")),
            UUID.fromString(rs.getString("recipient_id")),
            UUID.fromString(rs.getString("subscription_id")),
            rs.getInt("attempts"),
        )
    }

    private data class OutboxEntry(
        val id: UUID,
        val circleId: UUID,
        val requestId: UUID,
        val recipientId: UUID,
        val subscriptionId: UUID,
        val attempts: Int,
    )

    private companion object {
        const val BATCH_SIZE = 50
        const val LEASE_SECONDS = 120L
        const val RETRY_BASE_SECONDS = 30L
    }
}
