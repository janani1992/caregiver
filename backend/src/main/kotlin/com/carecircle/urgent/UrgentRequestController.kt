package com.carecircle.urgent

import com.carecircle.auth.CurrentUserProvider
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/v1")
class UrgentRequestController(
    private val urgentRequestService: UrgentRequestService,
    private val currentUserProvider: CurrentUserProvider,
) {
    @PostMapping("/circles/{circleId}/urgent")
    fun create(
        @PathVariable circleId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: CreateUrgentRequest,
    ): ResponseEntity<UrgentRequestResponse> = ResponseEntity
        .status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .body(urgentRequestService.create(circleId, currentUserProvider.currentUserId(), idempotencyKey, request))

    @GetMapping("/circles/{circleId}/urgent")
    fun list(@PathVariable circleId: UUID): ResponseEntity<UrgentRequestsResponse> = ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .body(urgentRequestService.list(circleId, currentUserProvider.currentUserId()))

    @PostMapping("/urgent/{requestId}/ack")
    fun acknowledge(
        @PathVariable requestId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: AcknowledgeUrgentRequest,
    ): ResponseEntity<UrgentRequestResponse> = ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .body(urgentRequestService.acknowledge(requestId, currentUserProvider.currentUserId(), idempotencyKey, request))

    @PostMapping("/urgent/{requestId}/resolve")
    fun resolve(
        @PathVariable requestId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
    ): ResponseEntity<UrgentRequestResponse> = ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .body(urgentRequestService.resolve(requestId, currentUserProvider.currentUserId(), idempotencyKey))
}
