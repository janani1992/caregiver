package com.carecircle.circles

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

@Service
class CircleService(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    fun create(request: CreateCircleRequest, ownerId: UUID, idempotencyKey: String): CircleResponse {
        val name = request.name.trim()
        val timezone = request.timezone.trim()
        if (name.isEmpty() || name.length > 80 || timezone.isEmpty() || timezone.length > 64) {
            throw InvalidCircleRequestException("Name and timezone are required")
        }
        runCatching { java.time.ZoneId.of(timezone) }
            .getOrElse { throw InvalidCircleRequestException("Unknown timezone") }

        val fingerprint = fingerprint(name, timezone, request.id)
        try {
            return transactions.execute {
                val existing = findByIdempotencyKey(ownerId, idempotencyKey)
                if (existing != null) return@execute verifyReplay(existing, fingerprint)

                jdbc.update(
                    "INSERT INTO circle (id, name, timezone, created_by, change_seq) VALUES (?, ?, ?, ?, 1)",
                    request.id, name, timezone, ownerId,
                )
                jdbc.update(
                    "INSERT INTO membership (circle_id, user_id, role) VALUES (?, ?, 'OWNER')",
                    request.id, ownerId,
                )
                jdbc.update(
                    "INSERT INTO idempotency_record (user_id, idempotency_key, request_hash, circle_id) VALUES (?, ?, ?, ?)",
                    ownerId, idempotencyKey, fingerprint, request.id,
                )
                CircleResponse(request.id, name, timezone, ownerId, changeSeq = 1)
            } ?: error("Circle transaction returned no result")
        } catch (_: DuplicateKeyException) {
            // A concurrent retry may win the unique-key race. Read after the failed transaction rolls back.
            val existing = findByIdempotencyKey(ownerId, idempotencyKey)
                ?: throw CircleConflictException("Circle ID already exists")
            return verifyReplay(existing, fingerprint)
        }
    }

    private fun findByIdempotencyKey(ownerId: UUID, key: String): Replay? = jdbc.query(
        "SELECT r.request_hash, c.id, c.name, c.timezone, c.created_by, c.change_seq " +
            "FROM idempotency_record r JOIN circle c ON c.id = r.circle_id " +
            "WHERE r.user_id = ? AND r.idempotency_key = ?",
        RowMapper { rs, _ ->
            Replay(
                rs.getString("request_hash"),
                CircleResponse(
                    UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("timezone"),
                    UUID.fromString(rs.getString("created_by")), changeSeq = rs.getLong("change_seq"),
                ),
            )
        },
        ownerId,
        key,
    ).firstOrNull()

    private fun verifyReplay(replay: Replay, requestHash: String): CircleResponse {
        if (replay.requestHash != requestHash) {
            throw CircleConflictException("Idempotency-Key was already used with a different request")
        }
        return replay.response
    }

    private fun fingerprint(name: String, timezone: String, id: UUID): String {
        val value = "$id\n$name\n$timezone".toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    }

    private data class Replay(val requestHash: String, val response: CircleResponse)
}
