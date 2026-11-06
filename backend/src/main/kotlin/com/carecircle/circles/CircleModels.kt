package com.carecircle.circles

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

data class CreateCircleRequest(
    val id: UUID,
    @field:NotBlank @field:Size(max = 80) val name: String,
    @field:NotBlank @field:Size(max = 64) val timezone: String,
)

data class CircleResponse(
    val id: UUID,
    val name: String,
    val timezone: String,
    val ownerUserId: UUID,
    val role: String = "OWNER",
    val changeSeq: Long,
)
