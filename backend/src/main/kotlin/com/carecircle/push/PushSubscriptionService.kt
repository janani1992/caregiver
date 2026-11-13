package com.carecircle.push

import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Service
class PushSubscriptionService(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val secretCipher: PushSecretCipher,
    @param:Value("\${care-circle.push.vapid-public-key:}") private val vapidPublicKey: String,
    @param:Value("\${care-circle.push.provider:log}") private val provider: String,
) {
    private val transactions = TransactionTemplate(transactionManager)

    fun configuration() = PushConfigurationResponse(
        vapidPublicKey.trim().takeIf(String::isNotEmpty).takeIf { secretCipher.isConfigured() },
        provider != "log" && secretCipher.isConfigured() && vapidPublicKey.isNotBlank(),
    )

    fun list(userId: UUID): PushSubscriptionsResponse {
        val subscriptions = jdbc.query(
            "SELECT id, endpoint_ciphertext, created_at FROM push_subscription WHERE user_id = ? AND disabled_at IS NULL ORDER BY created_at",
            subscriptionRowMapper,
            userId,
        )
        return PushSubscriptionsResponse(subscriptions)
    }

    fun register(userId: UUID, request: CreatePushSubscriptionRequest): PushSubscriptionResponse {
        val endpoint = request.endpoint.trim()
        validateEndpoint(endpoint)
        if (!secretCipher.isConfigured()) {
            throw PushSubscriptionInvalidRequestException("Push subscription storage is not configured")
        }
        val hasMembership = jdbc.query(
            "SELECT 1 FROM membership WHERE user_id = ? AND removed_at IS NULL",
            RowMapper { _, _ -> true },
            userId,
        ).isNotEmpty()
        if (!hasMembership) throw PushSubscriptionForbiddenException()

        try {
            return transactions.execute {
                val existing = jdbc.query(
                    "SELECT id, user_id, endpoint_ciphertext, created_at FROM push_subscription WHERE endpoint_hash = ? FOR UPDATE",
                    subscriptionRowMapperWithUser,
                    endpointHash(endpoint),
                ).firstOrNull()
                if (existing != null) {
                    if (existing.userId != userId) throw PushSubscriptionConflictException("This browser subscription is already linked to another account")
                    jdbc.update(
                        "UPDATE push_subscription SET endpoint_ciphertext = ?, p256dh_key = ?, auth_secret = ?, disabled_at = NULL, updated_at = ? WHERE id = ?",
                        secretCipher.encrypt(endpoint),
                        secretCipher.encrypt(request.keys.p256dh),
                        secretCipher.encrypt(request.keys.auth),
                        Timestamp.from(Instant.now()),
                        existing.id,
                    )
                    return@execute PushSubscriptionResponse(existing.id, existing.endpoint, existing.createdAt)
                }

                val id = UUID.randomUUID()
                val now = Instant.now()
                jdbc.update(
                    "INSERT INTO push_subscription (id, user_id, endpoint_hash, endpoint_ciphertext, p256dh_key, auth_secret, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    id,
                    userId,
                    endpointHash(endpoint),
                    secretCipher.encrypt(endpoint),
                    secretCipher.encrypt(request.keys.p256dh),
                    secretCipher.encrypt(request.keys.auth),
                    Timestamp.from(now),
                    Timestamp.from(now),
                )
                PushSubscriptionResponse(id, endpoint, now)
            } ?: error("Push subscription registration returned no result")
        } catch (_: DuplicateKeyException) {
            throw PushSubscriptionConflictException("This browser subscription is already registered")
        }
    }

    fun remove(userId: UUID, subscriptionId: UUID) {
        val updated = jdbc.update(
            "UPDATE push_subscription SET disabled_at = ?, updated_at = ? WHERE id = ? AND user_id = ? AND disabled_at IS NULL",
            Timestamp.from(Instant.now()),
            Timestamp.from(Instant.now()),
            subscriptionId,
            userId,
        )
        if (updated == 0) {
            val exists = jdbc.query(
                "SELECT 1 FROM push_subscription WHERE id = ? AND user_id = ?",
                RowMapper { _, _ -> true },
                subscriptionId,
                userId,
            ).isNotEmpty()
            if (!exists) throw PushSubscriptionNotFoundException()
        }
    }

    private fun validateEndpoint(endpoint: String) {
        val uri = runCatching { URI(endpoint) }.getOrNull()
        val host = uri?.host?.lowercase()
        val allowedPushService = host == "fcm.googleapis.com" ||
            host == "push.services.mozilla.com" ||
            host?.endsWith(".push.services.mozilla.com") == true ||
            host?.endsWith(".push.apple.com") == true
        if (
            uri == null || uri.scheme != "https" || host.isNullOrBlank() ||
            (uri.port != -1 && uri.port != 443) || uri.userInfo != null || uri.fragment != null ||
            !allowedPushService
        ) {
            throw PushSubscriptionInvalidRequestException("Push endpoint must be a supported HTTPS browser push-service URL")
        }
    }

    private fun endpointHash(endpoint: String): String = MessageDigest.getInstance("SHA-256")
        .digest(endpoint.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private data class StoredSubscription(
        val id: UUID,
        val userId: UUID,
        val endpoint: String,
        val createdAt: Instant,
    )

    private val subscriptionRowMapper = RowMapper { rs, _ ->
        PushSubscriptionResponse(
            UUID.fromString(rs.getString("id")),
            secretCipher.decrypt(rs.getString("endpoint_ciphertext")),
            rs.getTimestamp("created_at").toInstant(),
        )
    }

    private val subscriptionRowMapperWithUser = RowMapper { rs, _ ->
        StoredSubscription(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("user_id")),
            secretCipher.decrypt(rs.getString("endpoint_ciphertext")),
            rs.getTimestamp("created_at").toInstant(),
        )
    }
}
