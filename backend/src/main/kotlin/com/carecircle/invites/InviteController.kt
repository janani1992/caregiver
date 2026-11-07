package com.carecircle.invites

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
class InviteController(
    private val inviteService: InviteService,
    private val currentUserProvider: CurrentUserProvider,
) {
    @PostMapping("/v1/circles/{circleId}/invites")
    fun create(
        @PathVariable circleId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: CreateInviteRequest,
    ): ResponseEntity<InviteResponse> = ResponseEntity
        .status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .body(
            inviteService.create(
                circleId,
                currentUserProvider.currentUserId(),
                idempotencyKey,
                request,
            ),
        )

    @GetMapping("/v1/invites/{token}")
    fun preview(@PathVariable token: String): ResponseEntity<InvitePreviewResponse> = ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .body(inviteService.preview(token))

    @PostMapping("/v1/invites/{token}/accept")
    fun accept(
        @PathVariable token: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: AcceptInviteRequest,
    ): ResponseEntity<InviteAcceptanceResponse> = ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .body(inviteService.accept(token, currentUserProvider.currentUserId(), idempotencyKey, request))
}
