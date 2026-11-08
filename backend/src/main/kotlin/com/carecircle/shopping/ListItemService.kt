package com.carecircle.shopping

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
class ListItemService(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    fun create(circleId: UUID, actorId: UUID, idempotencyKey: String, request: CreateListItemRequest): ListItemResponse {
        validateIdempotencyKey(idempotencyKey)
        val text = request.text.trim()
        val unit = request.unit?.trim()?.takeIf(String::isNotEmpty)
        val shopGroup = request.shopGroup?.trim()?.takeIf(String::isNotEmpty)
        if (text.isEmpty() || text.length > 200) throw ListItemInvalidRequestException("Item text is required")
        if (request.qty != null && request.qty !in 1..999) throw ListItemInvalidRequestException("Quantity must be between 1 and 999")

        val requestHash = fingerprint(circleId, request.id, text, request.qty, unit, shopGroup)
        try {
            return transactions.execute {
                val role = activeRole(circleId, actorId) ?: throw ListItemForbiddenException()
                if (role !in WRITE_ROLES) throw ListItemForbiddenException()

                val replayItemId = findEventItem(actorId, idempotencyKey, requestHash)
                if (replayItemId != null) return@execute loadItem(replayItemId)?.let(::toResponse)
                    ?: throw ListItemNotFoundException()

                val existing = findItem(request.id)
                if (existing != null) {
                    throw ListItemConflictException("Item ID is already in use")
                }

                val now = Instant.now()
                jdbc.update(
                    "INSERT INTO list_item " +
                        "(id, circle_id, item_text, qty, unit, shop_group, status, version, request_hash, created_by, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 'NEEDED', 1, ?, ?, ?, ?)",
                    request.id,
                    circleId,
                    text,
                    request.qty,
                    unit,
                    shopGroup,
                    requestHash,
                    actorId,
                    Timestamp.from(now),
                    Timestamp.from(now),
                )
                recordEvent(circleId, request.id, actorId, "CREATED", idempotencyKey, requestHash, 1)
                incrementCircleSequence(circleId)
                loadItem(request.id)?.let(::toResponse) ?: throw ListItemNotFoundException()
            } ?: error("List-item creation transaction returned no result")
        } catch (_: DuplicateKeyException) {
            val replayItemId = findEventItem(actorId, idempotencyKey, requestHash)
            if (replayItemId != null) return loadItem(replayItemId)?.let(::toResponse)
                ?: throw ListItemNotFoundException()
            throw ListItemConflictException("Item ID or idempotency key is already in use")
        }
    }

    fun list(circleId: UUID, actorId: UUID, status: ListItemStatus?, shop: String?): ListItemsResponse {
        if (activeRole(circleId, actorId) == null) throw ListItemForbiddenException()
        val sql = StringBuilder(
            "SELECT i.id, i.circle_id, i.item_text, i.qty, i.unit, i.shop_group, i.status, i.claimed_by, " +
                "cp.display_name AS claimed_by_name, i.created_by, creator.display_name AS created_by_name, " +
                "i.created_at, i.updated_at, i.version, i.request_hash, i.removed_at " +
                "FROM list_item i " +
                "JOIN user_profile creator ON creator.user_id = i.created_by " +
                "LEFT JOIN user_profile cp ON cp.user_id = i.claimed_by " +
                "WHERE i.circle_id = ? AND i.removed_at IS NULL",
        )
        val args = mutableListOf<Any>(circleId)
        if (status != null) {
            sql.append(" AND i.status = ?")
            args.add(status.name)
        }
        val normalizedShop = shop?.trim()?.takeIf(String::isNotEmpty)
        if (normalizedShop != null) {
            sql.append(" AND LOWER(i.shop_group) = LOWER(?)")
            args.add(normalizedShop)
        }
        sql.append(" ORDER BY i.created_at DESC, i.id")
        val items = jdbc.query(sql.toString(), itemRowMapper, *args.toTypedArray()).map(::toResponse)
        return ListItemsResponse(circleId, items)
    }

    fun claim(itemId: UUID, actorId: UUID, idempotencyKey: String): ListItemResponse {
        validateIdempotencyKey(idempotencyKey)
        return transactions.execute {
            val item = lockItem(itemId)
            val role = activeRole(item.circleId, actorId) ?: throw ListItemForbiddenException()
            if (role !in WRITE_ROLES) throw ListItemForbiddenException()
            val requestHash = mutationFingerprint(item.circleId, itemId, "CLAIMED")
            val replayItemId = findEventItem(actorId, idempotencyKey, requestHash)
            if (replayItemId != null) return@execute loadItem(replayItemId)?.let(::toResponse)
                ?: throw ListItemNotFoundException()
            if (item.removedAt != null) throw ListItemNotFoundException()

            when (item.status) {
                ListItemStatus.NEEDED -> {
                    val now = Instant.now()
                    jdbc.update(
                        "UPDATE list_item SET status = 'CLAIMED', claimed_by = ?, version = version + 1, updated_at = ? " +
                            "WHERE id = ? AND status = 'NEEDED' AND version = ?",
                        actorId,
                        Timestamp.from(now),
                        itemId,
                        item.version,
                    )
                    recordEvent(item.circleId, itemId, actorId, "CLAIMED", idempotencyKey, requestHash, item.version + 1)
                    incrementCircleSequence(item.circleId)
                    loadItem(itemId)?.let(::toResponse) ?: throw ListItemNotFoundException()
                }
                ListItemStatus.CLAIMED -> {
                    if (item.claimedBy == actorId) toResponse(item)
                    else throw ListItemConflictException("This item has already been claimed")
                }
                else -> throw ListItemConflictException("Only needed items can be claimed")
            }
        } ?: error("Claim transaction returned no result")
    }

    fun markBought(
        itemId: UUID,
        actorId: UUID,
        idempotencyKey: String,
        expectedVersion: Long,
        request: UpdateListItemStatusRequest,
    ): ListItemResponse {
        validateIdempotencyKey(idempotencyKey)
        if (request.status != ListItemStatus.BOUGHT) {
            throw ListItemInvalidRequestException("Only BOUGHT is supported by this action")
        }
        return transactions.execute {
            val item = lockItem(itemId)
            val role = activeRole(item.circleId, actorId) ?: throw ListItemForbiddenException()
            if (role !in WRITE_ROLES) throw ListItemForbiddenException()
            val requestHash = mutationFingerprint(item.circleId, itemId, "BOUGHT:$expectedVersion")
            val replayItemId = findEventItem(actorId, idempotencyKey, requestHash)
            if (replayItemId != null) return@execute loadItem(replayItemId)?.let(::toResponse)
                ?: throw ListItemNotFoundException()
            if (item.removedAt != null) throw ListItemNotFoundException()
            if (item.version != expectedVersion) throw ListItemConflictException("Item changed; refresh and try again")
            if (item.status != ListItemStatus.CLAIMED) {
                throw ListItemConflictException("Only claimed items can be marked bought")
            }

            val now = Instant.now()
            jdbc.update(
                "UPDATE list_item SET status = 'BOUGHT', version = version + 1, updated_at = ? " +
                    "WHERE id = ? AND status = 'CLAIMED' AND version = ?",
                Timestamp.from(now),
                itemId,
                expectedVersion,
            )
            recordEvent(item.circleId, itemId, actorId, "BOUGHT", idempotencyKey, requestHash, item.version + 1)
            incrementCircleSequence(item.circleId)
            loadItem(itemId)?.let(::toResponse) ?: throw ListItemNotFoundException()
        } ?: error("Status transaction returned no result")
    }

    fun remove(itemId: UUID, actorId: UUID, idempotencyKey: String) {
        validateIdempotencyKey(idempotencyKey)
        transactions.executeWithoutResult {
            val item = lockItem(itemId)
            val role = activeRole(item.circleId, actorId) ?: throw ListItemForbiddenException()
            if (role !in DELETE_ROLES) throw ListItemForbiddenException()
            val requestHash = mutationFingerprint(item.circleId, itemId, "REMOVED")
            if (findEventItem(actorId, idempotencyKey, requestHash) != null) return@executeWithoutResult
            if (item.removedAt != null) return@executeWithoutResult

            val now = Instant.now()
            jdbc.update(
                "UPDATE list_item SET removed_at = ?, removed_by = ?, version = version + 1, updated_at = ? " +
                    "WHERE id = ? AND removed_at IS NULL",
                Timestamp.from(now),
                actorId,
                Timestamp.from(now),
                itemId,
            )
            recordEvent(item.circleId, itemId, actorId, "REMOVED", idempotencyKey, requestHash, item.version + 1)
            incrementCircleSequence(item.circleId)
        }
    }

    private fun activeRole(circleId: UUID, userId: UUID): String? = jdbc.query(
        "SELECT role FROM membership WHERE circle_id = ? AND user_id = ? AND removed_at IS NULL",
        RowMapper { rs, _ -> rs.getString("role") },
        circleId,
        userId,
    ).firstOrNull()

    private fun lockItem(itemId: UUID): StoredListItem = jdbc.query(
        itemSelect("WHERE i.id = ? FOR UPDATE"),
        itemRowMapper,
        itemId,
    ).firstOrNull() ?: throw ListItemNotFoundException()

    private fun loadItem(itemId: UUID): StoredListItem? = jdbc.query(
        itemSelect("WHERE i.id = ?"),
        itemRowMapper,
        itemId,
    ).firstOrNull()

    private fun findItem(itemId: UUID): StoredListItem? = jdbc.query(
        "SELECT id, circle_id, item_text, qty, unit, shop_group, status, claimed_by, NULL AS claimed_by_name, " +
            "created_by, '' AS created_by_name, created_at, updated_at, version, request_hash, removed_at " +
            "FROM list_item WHERE id = ?",
        itemRowMapper,
        itemId,
    ).firstOrNull()

    private fun itemSelect(where: String) =
        "SELECT i.id, i.circle_id, i.item_text, i.qty, i.unit, i.shop_group, i.status, i.claimed_by, " +
            "cp.display_name AS claimed_by_name, i.created_by, creator.display_name AS created_by_name, " +
            "i.created_at, i.updated_at, i.version, i.request_hash, i.removed_at " +
            "FROM list_item i " +
            "JOIN user_profile creator ON creator.user_id = i.created_by " +
            "LEFT JOIN user_profile cp ON cp.user_id = i.claimed_by " +
            where

    private fun recordEvent(
        circleId: UUID,
        itemId: UUID,
        actorId: UUID,
        eventType: String,
        idempotencyKey: String,
        requestHash: String,
        version: Long,
    ) {
        jdbc.update(
            "INSERT INTO list_item_event " +
                "(circle_id, item_id, actor, event_type, idempotency_key, request_hash, payload, at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            circleId,
            itemId,
            actorId,
            eventType,
            idempotencyKey,
            requestHash,
            "{\"version\":$version}",
            Timestamp.from(Instant.now()),
        )
    }

    private fun findEventItem(actorId: UUID, idempotencyKey: String, requestHash: String): UUID? {
        val event = jdbc.query(
            "SELECT item_id, request_hash FROM list_item_event WHERE actor = ? AND idempotency_key = ?",
            RowMapper { rs, _ -> rs.getString("item_id") to rs.getString("request_hash") },
            actorId,
            idempotencyKey,
        ).firstOrNull() ?: return null
        if (event.second != requestHash) {
            throw ListItemConflictException("Idempotency-Key was already used for a different request")
        }
        return UUID.fromString(event.first)
    }

    private fun incrementCircleSequence(circleId: UUID) {
        jdbc.update("UPDATE circle SET change_seq = change_seq + 1 WHERE id = ?", circleId)
    }

    private fun validateIdempotencyKey(key: String) {
        if (key.isBlank() || key.length > 128) {
            throw ListItemInvalidRequestException("Idempotency-Key must be 1-128 characters")
        }
    }

    private fun fingerprint(
        circleId: UUID,
        itemId: UUID,
        text: String,
        qty: Int?,
        unit: String?,
        shopGroup: String?,
    ): String = hash("CREATE\n$circleId\n$itemId\n$text\n${qty ?: ""}\n${unit.orEmpty()}\n${shopGroup.orEmpty()}")

    private fun mutationFingerprint(circleId: UUID, itemId: UUID, action: String): String =
        hash("$action\n$circleId\n$itemId")

    private fun hash(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun toResponse(item: StoredListItem) = ListItemResponse(
        item.id,
        item.circleId,
        item.text,
        item.qty,
        item.unit,
        item.shopGroup,
        item.status,
        item.claimedBy,
        item.claimedByName,
        item.createdBy,
        item.createdByName,
        item.createdAt,
        item.updatedAt,
        item.version,
    )

    private val itemRowMapper = RowMapper { rs, _ ->
        val qty = rs.getInt("qty").let { value -> if (rs.wasNull()) null else value }
        StoredListItem(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("circle_id")),
            rs.getString("item_text"),
            qty,
            rs.getString("unit"),
            rs.getString("shop_group"),
            ListItemStatus.valueOf(rs.getString("status")),
            rs.getString("claimed_by")?.let(UUID::fromString),
            rs.getString("claimed_by_name"),
            UUID.fromString(rs.getString("created_by")),
            rs.getString("created_by_name"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getLong("version"),
            rs.getString("request_hash"),
            rs.getTimestamp("removed_at")?.toInstant(),
        )
    }

    private data class StoredListItem(
        val id: UUID,
        val circleId: UUID,
        val text: String,
        val qty: Int?,
        val unit: String?,
        val shopGroup: String?,
        val status: ListItemStatus,
        val claimedBy: UUID?,
        val claimedByName: String?,
        val createdBy: UUID,
        val createdByName: String,
        val createdAt: Instant,
        val updatedAt: Instant,
        val version: Long,
        val requestHash: String,
        val removedAt: Instant?,
    )

    private companion object {
        val WRITE_ROLES = setOf("OWNER", "FAMILY", "HELPER")
        val DELETE_ROLES = setOf("OWNER", "FAMILY")
    }
}
