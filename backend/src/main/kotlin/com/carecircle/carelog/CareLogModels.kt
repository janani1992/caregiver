package com.carecircle.carelog

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

enum class CareLogCategory {
    GENERAL,
    MEDICATION,
    APPOINTMENT,
    WELLBEING,
    OTHER,
}

data class CreateCareLogEntryRequest(
    val id: UUID,
    @field:NotBlank @field:Size(max = 2000) val text: String,
    val category: CareLogCategory = CareLogCategory.GENERAL,
)

data class CareLogEntryResponse(
    val id: UUID,
    val circleId: UUID,
    val text: String,
    val category: CareLogCategory,
    val authorId: UUID,
    val authorName: String,
    val createdAt: Instant,
)

data class CareLogPageResponse(
    val circleId: UUID,
    val entries: List<CareLogEntryResponse>,
    val nextCursor: String?,
)

class CareLogForbiddenException : RuntimeException("Your circle role cannot perform this action")
class CareLogConflictException(message: String) : RuntimeException(message)
class CareLogInvalidRequestException(message: String) : RuntimeException(message)
class CareLogEncryptionUnavailableException : RuntimeException("Care log encryption is not configured")