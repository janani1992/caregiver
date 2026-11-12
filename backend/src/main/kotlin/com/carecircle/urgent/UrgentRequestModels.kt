package com.carecircle.urgent

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

enum class UrgentRequestState {
    OPEN,
    ON_IT,
    RESOLVED,
}

enum class UrgentAcknowledgement {
    SEEN,
    ON_IT,
}

data class CreateUrgentRequest(
    val id: UUID,
    val itemId: UUID? = null,
    @field:NotBlank @field:Size(max = 500) val text: String,
)

data class AcknowledgeUrgentRequest(
    val acknowledgement: UrgentAcknowledgement,
)

data class UrgentRequestsResponse(
    val circleId: UUID,
    val requests: List<UrgentRequestResponse>,
)

data class UrgentRequestResponse(
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

class UrgentRequestForbiddenException : RuntimeException("Your circle role cannot perform this action")
class UrgentRequestNotFoundException : RuntimeException("Urgent request not found")
class UrgentRequestConflictException(message: String) : RuntimeException(message)
class UrgentRequestInvalidRequestException(message: String) : RuntimeException(message)
