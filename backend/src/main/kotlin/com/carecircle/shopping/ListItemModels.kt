package com.carecircle.shopping

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

enum class ListItemStatus {
    NEEDED,
    CLAIMED,
    BOUGHT,
    NOT_FOUND,
}

data class CreateListItemRequest(
    val id: UUID,
    @field:NotBlank @field:Size(max = 200) val text: String,
    @field:Min(1) @field:Max(999) val qty: Int? = null,
    @field:Size(max = 32) val unit: String? = null,
    @field:Size(max = 64) val shopGroup: String? = null,
)

data class UpdateListItemStatusRequest(
    val status: ListItemStatus,
)

data class ListItemsResponse(
    val circleId: UUID,
    val items: List<ListItemResponse>,
)

data class ListItemResponse(
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
)

class ListItemForbiddenException : RuntimeException("Your circle role cannot perform this action")
class ListItemNotFoundException : RuntimeException("List item not found")
class ListItemConflictException(message: String) : RuntimeException(message)
class ListItemInvalidRequestException(message: String) : RuntimeException(message)
