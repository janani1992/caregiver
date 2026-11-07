package com.carecircle.invites

import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

enum class InviteRole {
    FAMILY,
    HELPER,
    VIEWER,
}

data class CreateInviteRequest(
    val id: UUID,
    val role: InviteRole,
    @field:Pattern(regexp = "^[A-Za-z0-9_-]{43}$") val token: String,
)

data class AcceptInviteRequest(
    @field:NotBlank @field:Size(max = 80) val displayName: String,
)

data class InviteResponse(
    val id: UUID,
    val circleId: UUID,
    val role: InviteRole,
    val inviteUrl: String,
    val expiresAt: Instant,
)

data class InvitePreviewResponse(
    val circleId: UUID,
    val circleName: String,
    val role: InviteRole,
    val expiresAt: Instant,
)

data class InviteAcceptanceResponse(
    val circleId: UUID,
    val circleName: String,
    val role: InviteRole,
    val acceptedAt: Instant,
    val alreadyAccepted: Boolean,
)

class InviteNotFoundException : RuntimeException("Invite link is invalid or no longer available")
class InviteExpiredException : RuntimeException("This invite has expired")
class InviteConflictException(message: String) : RuntimeException(message)
class InviteForbiddenException : RuntimeException("Only a circle owner can create invites")
class InviteInvalidRequestException(message: String) : RuntimeException(message)
