package com.carecircle.members

import com.carecircle.auth.CurrentUserProvider
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class MemberController(
    private val memberService: MemberService,
    private val currentUserProvider: CurrentUserProvider,
) {
    @GetMapping("/v1/circles/{circleId}/members")
    fun list(@PathVariable circleId: UUID): ResponseEntity<CircleMembersResponse> = ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .body(memberService.list(circleId, currentUserProvider.currentUserId()))

    @DeleteMapping("/v1/circles/{circleId}/members/{userId}")
    fun remove(
        @PathVariable circleId: UUID,
        @PathVariable userId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
    ): ResponseEntity<Void> {
        memberService.remove(circleId, currentUserProvider.currentUserId(), userId, idempotencyKey)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).cacheControl(CacheControl.noStore()).build()
    }
}
