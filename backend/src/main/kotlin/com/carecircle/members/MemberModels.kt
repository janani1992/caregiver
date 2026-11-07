package com.carecircle.members

import java.time.Instant
import java.util.UUID

enum class MemberRole {
    OWNER,
    FAMILY,
    HELPER,
    VIEWER,
}

data class CircleMembersResponse(
    val circleId: UUID,
    val circleName: String,
    val currentUserRole: MemberRole,
    val members: List<MemberSummary>,
)

data class MemberSummary(
    val userId: UUID,
    val displayName: String,
    val role: MemberRole,
    val joinedAt: Instant,
)

class MemberForbiddenException : RuntimeException("An active circle membership is required")
class MemberNotFoundException : RuntimeException("Circle member not found")
class MemberConflictException(message: String) : RuntimeException(message)
class MemberInvalidRequestException(message: String) : RuntimeException(message)
