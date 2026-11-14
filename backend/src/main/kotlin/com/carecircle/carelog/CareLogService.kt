package com.carecircle.carelog

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

@Service
class CareLogService(
    private val jdbc: JdbcTemplate,
    private val cipher: CareLogCipher,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    fun create(circleId: UUID, actorId: UUID, idempotencyKey: String, request: CreateCareLogEntryRequest): CareLogEntryResponse {
        validateIdempotencyKey(idempotencyKey)
        val text = request.text.trim()
        if (text.isEmpty() || text.length > MAX_TEXT_LENGTH) {
            throw CareLogInvalidRequestException("Entry text must contain 1-2000 characters")
        }
        val requestHash = fingerprint(circleId, request.id, text, request.category)
        try {
            return transactions.execute {
                val role = activeRole(circleId, actorId) ?: throw CareLogForbiddenException()
                if (role !in WRITE_ROLES) throw CareLogForbiddenException()
                if (!cipher.isConfigured()) throw CareLogEncryptionUnavailableException()

                findByIdempotencyKey(actorId, idempotencyKey, requestHash)?.let { existing ->
                    return@execute loadEntry(existing)?.let(::toResponse) ?: throw CareLogConflictException("Care log entry not found")
                }
                if (entryExists(request.id)) throw CareLogConflictException("Care log entry ID is already in use")

                val now = Instant.now()
                jdbc.update(
                    "INSERT INTO care_log_entry " +
                        "(id, circle_id, author_id, body_ciphertext, category, request_hash, idempotency_key, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    request.id,
                    circleId,
                    actorId,
                    cipher.encrypt(text, associatedData(circleId, request.id)),
                    request.category.name,
                    requestHash,
                    idempotencyKey,
                    Timestamp.from(now),
                )
                jdbc.update("UPDATE circle SET change_seq = change_seq + 1 WHERE id = ?", circleId)
                loadEntry(request.id)?.let(::toResponse) ?: throw CareLogConflictException("Care log entry not found")
            } ?: error("Care log creation transaction returned no result")
        } catch (_: DuplicateKeyException) {
            findByIdempotencyKey(actorId, idempotencyKey, requestHash)?.let { entryId ->
                return loadEntry(entryId)?.let(::toResponse) ?: throw CareLogConflictException("Care log entry not found")
            }
            throw CareLogConflictException("Care log entry ID or idempotency key is already in use")
        }
    }

    fun list(circleId: UUID, actorId: UUID, cursor: String?, limit: Int): CareLogPageResponse {
        if (activeRole(circleId, actorId) == null) throw CareLogForbiddenException()
        if (limit !in MIN_PAGE_SIZE..MAX_PAGE_SIZE) {
            throw CareLogInvalidRequestException("Limit must be between $MIN_PAGE_SIZE and $MAX_PAGE_SIZE")
        }
        if (!cipher.isConfigured()) throw CareLogEncryptionUnavailableException()
        val position = cursor?.let(::decodeCursor)
        val sql = StringBuilder(
            "SELECT e.id, e.circle_id, e.author_id, e.body_ciphertext, e.category, e.created_at, " +
                "p.display_name AS author_name FROM care_log_entry e " +
                "JOIN user_profile p ON p.user_id = e.author_id " +
                "WHERE e.circle_id = ?",
        )
        val args = mutableListOf<Any>(circleId)
        if (position != null) {
            sql.append(" AND (e.created_at < ? OR (e.created_at = ? AND e.id < ?))")
            val timestamp = Timestamp.from(position.createdAt)
            args.add(timestamp)
            args.add(timestamp)
            args.add(position.id)
        }
        sql.append(" ORDER BY e.created_at DESC, e.id DESC LIMIT ?")
        args.add(limit + 1)

        val results = jdbc.query(sql.toString(), entryRowMapper, *args.toTypedArray())
        val hasMore = results.size > limit
        val page = results.take(limit)
        val nextCursor = if (hasMore) page.lastOrNull()?.let { encodeCursor(Cursor(it.createdAt, it.id)) } else null
        return CareLogPageResponse(circleId, page.map(::toResponse), nextCursor)
    }

    private fun activeRole(circleId: UUID, userId: UUID): String? = jdbc.query(
        "SELECT role FROM membership WHERE circle_id = ? AND user_id = ? AND removed_at IS NULL",
        RowMapper { rs, _ -> rs.getString("role") },
        circleId,
        userId,
    ).firstOrNull()

    private fun entryExists(entryId: UUID): Boolean = jdbc.query(
        "SELECT id FROM care_log_entry WHERE id = ?",
        RowMapper { rs, _ -> rs.getString("id") },
        entryId,
    ).isNotEmpty()

    private fun findByIdempotencyKey(actorId: UUID, key: String, requestHash: String): UUID? {
        val existing = jdbc.query(
            "SELECT id, request_hash FROM care_log_entry WHERE author_id = ? AND idempotency_key = ?",
            RowMapper { rs, _ -> rs.getString("id") to rs.getString("request_hash") },
            actorId,
            key,
        ).firstOrNull() ?: return null
        if (existing.second != requestHash) {
            throw CareLogConflictException("Idempotency-Key was already used for a different request")
        }
        return UUID.fromString(existing.first)
    }

    private fun loadEntry(entryId: UUID): StoredCareLogEntry? = jdbc.query(
        "SELECT e.id, e.circle_id, e.author_id, e.body_ciphertext, e.category, e.created_at, " +
            "p.display_name AS author_name FROM care_log_entry e " +
            "JOIN user_profile p ON p.user_id = e.author_id WHERE e.id = ?",
        entryRowMapper,
        entryId,
    ).firstOrNull()

    private fun toResponse(entry: StoredCareLogEntry) = CareLogEntryResponse(
        entry.id,
        entry.circleId,
        cipher.decrypt(entry.bodyCiphertext, associatedData(entry.circleId, entry.id)),
        entry.category,
        entry.authorId,
        entry.authorName,
        entry.createdAt,
    )

    private fun encodeCursor(cursor: Cursor): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("${cursor.createdAt}|${cursor.id}".toByteArray(StandardCharsets.UTF_8))

    private fun decodeCursor(value: String): Cursor {
        if (value.length > MAX_CURSOR_LENGTH) throw CareLogInvalidRequestException("Invalid cursor")
        return try {
            val decoded = Base64.getUrlDecoder().decode(value).toString(StandardCharsets.UTF_8)
            val separator = decoded.lastIndexOf('|')
            if (separator <= 0) throw IllegalArgumentException("Invalid cursor")
            Cursor(Instant.parse(decoded.substring(0, separator)), UUID.fromString(decoded.substring(separator + 1)))
        } catch (_: IllegalArgumentException) {
            throw CareLogInvalidRequestException("Invalid cursor")
        } catch (_: DateTimeParseException) {
            throw CareLogInvalidRequestException("Invalid cursor")
        }
    }

    private fun validateIdempotencyKey(key: String) {
        if (key.isBlank() || key.length > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw CareLogInvalidRequestException("Idempotency-Key must be 1-128 characters")
        }
    }

    private fun fingerprint(circleId: UUID, entryId: UUID, text: String, category: CareLogCategory): String = hash(
        "CREATE\n$circleId\n$entryId\n$text\n${category.name}",
    )

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun associatedData(circleId: UUID, entryId: UUID) = "$circleId:$entryId"

    private val entryRowMapper = RowMapper { rs, _ ->
        StoredCareLogEntry(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("circle_id")),
            UUID.fromString(rs.getString("author_id")),
            rs.getString("body_ciphertext"),
            CareLogCategory.valueOf(rs.getString("category")),
            rs.getString("author_name"),
            rs.getTimestamp("created_at").toInstant(),
        )
    }

    private data class StoredCareLogEntry(
        val id: UUID,
        val circleId: UUID,
        val authorId: UUID,
        val bodyCiphertext: String,
        val category: CareLogCategory,
        val authorName: String,
        val createdAt: Instant,
    )

    private data class Cursor(val createdAt: Instant, val id: UUID)

    private companion object {
        const val MAX_TEXT_LENGTH = 2000
        const val MAX_CURSOR_LENGTH = 256
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 128
        const val MIN_PAGE_SIZE = 1
        const val MAX_PAGE_SIZE = 50
        val WRITE_ROLES = setOf("OWNER", "FAMILY", "HELPER")
    }
}