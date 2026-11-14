package com.carecircle.carelog

import com.carecircle.auth.CurrentUserProvider
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/v1")
class CareLogController(
    private val careLogService: CareLogService,
    private val currentUserProvider: CurrentUserProvider,
) {
    @PostMapping("/circles/{circleId}/log")
    fun create(
        @PathVariable circleId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: CreateCareLogEntryRequest,
    ): ResponseEntity<CareLogEntryResponse> = ResponseEntity
        .status(201)
        .cacheControl(CacheControl.noStore())
        .body(careLogService.create(circleId, currentUserProvider.currentUserId(), idempotencyKey, request))

    @GetMapping("/circles/{circleId}/log")
    fun list(
        @PathVariable circleId: UUID,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): ResponseEntity<CareLogPageResponse> = ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .body(careLogService.list(circleId, currentUserProvider.currentUserId(), cursor, limit))
}