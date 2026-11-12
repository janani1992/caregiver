package com.carecircle.urgent

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
import java.util.UUID

@Service
class UrgentRequestService(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    fun create(
        circleId: UUID,
        actorId: UUID,
        idempotencyKey: String,
        request: CreateUrgentRequest,
    ): UrgentRequestResponse {
        validateIdempotencyKey(idempotencyKey)
        val text = request.text.trim()
        if (text.isEmpty() || text.length > 500) throw UrgentRequestInvalidRequestException("Request text is required")
        val requestHash = hash("CREATE\n$circleId\n${request.id}\n${request.itemId ?: ""}\n$text")

        try {
            return transactions.execute {
                val role = activeRole(circleId, actorId) ?: throw UrgentRequestForbiddenException()
                if (role !in CREATE_ROLES) throw UrgentRequestForbiddenException()
                val replayId = findEventRequest(actorId, idempotencyKey, requestHash)
                if (replayId != null) return@execute loadResponse(replayId, actorId)

                if (request.itemId != null) {
                    val itemExists = jdbc.query(
                        "SELECT id FROM list_item WHERE id = ? AND circle_id = ? AND removed_at IS NULL",
                        RowMapper { rs, _ -> rs.getString("id") },
                        request.itemId,
                        circleId,
                    ).isNotEmpty()
                    if (!itemExists) throw UrgentRequestInvalidRequestException("Linked item is not available in this circle")
                }
                if (jdbc.query("SELECT id FROM urgent_request WHERE id = ?", RowMapper { rs, _ -> rs.getString("id") }, request.id).isNotEmpty()) {
                    throw UrgentRequestConflictException("Urgent request ID is already in use")
                }

                val now = Instant.now()
                jdbc.update(
                    "INSERT INTO urgent_request " +
                        "(id, circle_id, item_id, text, state, sender, version, request_hash, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, 'OPEN', ?, 1, ?, ?, ?)",
                    request.id,
                    circleId,
                    request.itemId,
                    text,
                    actorId,
                    requestHash,
                    Timestamp.from(now),
                    Timestamp.from(now),
                )
                recordEvent(circleId, request.id, actorId, "CREATED", idempotencyKey, requestHash)
                incrementCircleSequence(circleId)
                loadResponse(request.id, actorId)
            } ?: error("Urgent request creation returned no result")
        } catch (_: DuplicateKeyException) {
            val replayId = findEventRequest(actorId, idempotencyKey, requestHash)
                ?: throw UrgentRequestConflictException("Urgent request ID or idempotency key is already in use")
            return loadResponse(replayId, actorId)
        }
    }

    fun list(circleId: UUID, actorId: UUID): UrgentRequestsResponse {
        if (activeRole(circleId, actorId) == null) throw UrgentRequestForbiddenException()
        val requests = jdbc.query(requestSelect("WHERE r.circle_id = ? ORDER BY r.created_at DESC, r.id"), requestRowMapper, actorId, circleId)
        return UrgentRequestsResponse(circleId, requests.map(::toResponse))
    }

    fun acknowledge(
        requestId: UUID,
        actorId: UUID,
        idempotencyKey: String,
        request: AcknowledgeUrgentRequest,
    ): UrgentRequestResponse {
        validateIdempotencyKey(idempotencyKey)
        return transactions.execute {
            val stored = lockRequest(requestId, actorId)
            val role = activeRole(stored.circleId, actorId) ?: throw UrgentRequestForbiddenException()
            val action = request.acknowledgement.name
            val requestHash = hash("$action\n${stored.circleId}\n$requestId")
            val replayId = findEventRequest(actorId, idempotencyKey, requestHash)
            if (replayId != null) return@execute loadResponse(replayId, actorId)

            when (request.acknowledgement) {
                UrgentAcknowledgement.SEEN -> {
                    if (hasSeen(requestId, actorId)) return@execute loadResponse(requestId, actorId)
                    recordEvent(stored.circleId, requestId, actorId, "SEEN", idempotencyKey, requestHash)
                    incrementCircleSequence(stored.circleId)
                    loadResponse(requestId, actorId)
                }
                UrgentAcknowledgement.ON_IT -> {
                    if (role !in ACTION_ROLES) throw UrgentRequestForbiddenException()
                    if (stored.state == UrgentRequestState.ON_IT && stored.handledBy == actorId) {
                        return@execute loadResponse(requestId, actorId)
                    }
                    if (stored.state != UrgentRequestState.OPEN) {
                        throw UrgentRequestConflictException("This request is no longer open")
                    }
                    val now = Instant.now()
                    jdbc.update(
                        "UPDATE urgent_request SET state = 'ON_IT', handled_by = ?, handled_at = ?, version = version + 1, updated_at = ? " +
                            "WHERE id = ? AND state = 'OPEN' AND version = ?",
                        actorId,
                        Timestamp.from(now),
                        Timestamp.from(now),
                        requestId,
                        stored.version,
                    )
                    recordEvent(stored.circleId, requestId, actorId, "ON_IT", idempotencyKey, requestHash)
                    incrementCircleSequence(stored.circleId)
                    loadResponse(requestId, actorId)
                }
            }
        } ?: error("Urgent acknowledgement returned no result")
    }

    fun resolve(requestId: UUID, actorId: UUID, idempotencyKey: String): UrgentRequestResponse {
        validateIdempotencyKey(idempotencyKey)
        return transactions.execute {
            val stored = lockRequest(requestId, actorId)
            val role = activeRole(stored.circleId, actorId) ?: throw UrgentRequestForbiddenException()
            val canResolve = role in OWNER_FAMILY_ROLES || stored.sender == actorId || stored.handledBy == actorId
            if (!canResolve) throw UrgentRequestForbiddenException()
            val requestHash = hash("RESOLVED\n${stored.circleId}\n$requestId")
            val replayId = findEventRequest(actorId, idempotencyKey, requestHash)
            if (replayId != null) return@execute loadResponse(replayId, actorId)
            if (stored.state == UrgentRequestState.RESOLVED) {
                return@execute loadResponse(requestId, actorId)
            }

            val now = Instant.now()
            jdbc.update(
                "UPDATE urgent_request SET state = 'RESOLVED', resolved_by = ?, resolved_at = ?, version = version + 1, updated_at = ? " +
                    "WHERE id = ? AND state <> 'RESOLVED' AND version = ?",
                actorId,
                Timestamp.from(now),
                Timestamp.from(now),
                requestId,
                stored.version,
            )
            recordEvent(stored.circleId, requestId, actorId, "RESOLVED", idempotencyKey, requestHash)
            incrementCircleSequence(stored.circleId)
            loadResponse(requestId, actorId)
        } ?: error("Urgent request resolution returned no result")
    }

    private fun activeRole(circleId: UUID, userId: UUID): String? = jdbc.query(
        "SELECT role FROM membership WHERE circle_id = ? AND user_id = ? AND removed_at IS NULL",
        RowMapper { rs, _ -> rs.getString("role") },
        circleId,
        userId,
    ).firstOrNull()

    private fun lockRequest(requestId: UUID, actorId: UUID): StoredUrgentRequest {
        val exists = jdbc.query(
            "SELECT id FROM urgent_request WHERE id = ? FOR UPDATE",
            RowMapper { rs, _ -> rs.getString("id") },
            requestId,
        ).isNotEmpty()
        if (!exists) throw UrgentRequestNotFoundException()
        return loadRequest(requestId, actorId) ?: throw UrgentRequestNotFoundException()
    }

    private fun loadRequest(requestId: UUID, actorId: UUID): StoredUrgentRequest? = jdbc.query(
        requestSelect("WHERE r.id = ?"),
        requestRowMapper,
        actorId,
        requestId,
    ).firstOrNull()

    private fun loadResponse(requestId: UUID, actorId: UUID): UrgentRequestResponse =
        loadRequest(requestId, actorId)?.let(::toResponse) ?: throw UrgentRequestNotFoundException()

    private fun requestSelect(where: String) =
        "SELECT r.id, r.circle_id, r.item_id, r.text, r.state, r.sender, sender.display_name AS sender_name, " +
            "r.created_at, r.handled_by, handler.display_name AS handler_name, r.handled_at, " +
            "r.resolved_by, resolver.display_name AS resolver_name, r.resolved_at, r.version, " +
            "EXISTS (SELECT 1 FROM urgent_request_event seen WHERE seen.request_id = r.id AND seen.actor = ? AND seen.event_type = 'SEEN') AS seen_by_me " +
            "FROM urgent_request r " +
            "JOIN user_profile sender ON sender.user_id = r.sender " +
            "LEFT JOIN user_profile handler ON handler.user_id = r.handled_by " +
            "LEFT JOIN user_profile resolver ON resolver.user_id = r.resolved_by " +
            where

    private fun recordEvent(
        circleId: UUID,
        requestId: UUID,
        actorId: UUID,
        eventType: String,
        idempotencyKey: String,
        requestHash: String,
    ) {
        jdbc.update(
            "INSERT INTO urgent_request_event " +
                "(circle_id, request_id, actor, event_type, idempotency_key, request_hash) VALUES (?, ?, ?, ?, ?, ?)",
            circleId,
            requestId,
            actorId,
            eventType,
            idempotencyKey,
            requestHash,
        )
    }

    private fun findEventRequest(actorId: UUID, idempotencyKey: String, requestHash: String): UUID? {
        val event = jdbc.query(
            "SELECT request_id, request_hash FROM urgent_request_event WHERE actor = ? AND idempotency_key = ?",
            RowMapper { rs, _ -> rs.getString("request_id") to rs.getString("request_hash") },
            actorId,
            idempotencyKey,
        ).firstOrNull() ?: return null
        if (event.second != requestHash) {
            throw UrgentRequestConflictException("Idempotency-Key was already used for a different request")
        }
        return UUID.fromString(event.first)
    }

    private fun hasSeen(requestId: UUID, actorId: UUID): Boolean = jdbc.query(
        "SELECT 1 FROM urgent_request_event WHERE request_id = ? AND actor = ? AND event_type = 'SEEN'",
        RowMapper { _, _ -> true },
        requestId,
        actorId,
    ).isNotEmpty()

    private fun incrementCircleSequence(circleId: UUID) {
        jdbc.update("UPDATE circle SET change_seq = change_seq + 1 WHERE id = ?", circleId)
    }

    private fun validateIdempotencyKey(key: String) {
        if (key.isBlank() || key.length > 128) {
            throw UrgentRequestInvalidRequestException("Idempotency-Key must be 1-128 characters")
        }
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun toResponse(request: StoredUrgentRequest) = UrgentRequestResponse(
        request.id,
        request.circleId,
        request.itemId,
        request.text,
        request.state,
        request.sender,
        request.senderName,
        request.createdAt,
        request.handledBy,
        request.handledByName,
        request.handledAt,
        request.resolvedBy,
        request.resolvedByName,
        request.resolvedAt,
        request.version,
        request.seenByMe,
    )

    private val requestRowMapper = RowMapper { rs, _ ->
        StoredUrgentRequest(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("circle_id")),
            rs.getString("item_id")?.let(UUID::fromString),
            rs.getString("text"),
            UrgentRequestState.valueOf(rs.getString("state")),
            UUID.fromString(rs.getString("sender")),
            rs.getString("sender_name"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("handled_by")?.let(UUID::fromString),
            rs.getString("handler_name"),
            rs.getTimestamp("handled_at")?.toInstant(),
            rs.getString("resolved_by")?.let(UUID::fromString),
            rs.getString("resolver_name"),
            rs.getTimestamp("resolved_at")?.toInstant(),
            rs.getLong("version"),
            rs.getBoolean("seen_by_me"),
        )
    }

    private data class StoredUrgentRequest(
        val id: UUID,
        val circleId: UUID,
        val itemId: UUID?,
        val text: String,
        val state: UrgentRequestState,
        val sender: UUID,
        val senderName: String,
        val createdAt: Instant,
        val handledBy: UUID?,
        val handledByName: String?,
        val handledAt: Instant?,
        val resolvedBy: UUID?,
        val resolvedByName: String?,
        val resolvedAt: Instant?,
        val version: Long,
        val seenByMe: Boolean,
    )

    private companion object {
        val CREATE_ROLES = setOf("OWNER", "FAMILY")
        val ACTION_ROLES = setOf("OWNER", "FAMILY", "HELPER")
        val OWNER_FAMILY_ROLES = setOf("OWNER", "FAMILY")
    }
}
