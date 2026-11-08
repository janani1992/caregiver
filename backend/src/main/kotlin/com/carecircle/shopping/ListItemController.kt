package com.carecircle.shopping

import com.carecircle.auth.CurrentUserProvider
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
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
class ListItemController(
    private val itemService: ListItemService,
    private val currentUserProvider: CurrentUserProvider,
) {
    @PostMapping("/circles/{circleId}/items")
    fun create(
        @PathVariable circleId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: CreateListItemRequest,
    ): ResponseEntity<ListItemResponse> = ResponseEntity
        .status(201)
        .cacheControl(CacheControl.noStore())
        .body(itemService.create(circleId, currentUserProvider.currentUserId(), idempotencyKey, request))

    @GetMapping("/circles/{circleId}/items")
    fun list(
        @PathVariable circleId: UUID,
        @RequestParam(required = false) status: ListItemStatus?,
        @RequestParam(required = false) shop: String?,
    ): ResponseEntity<ListItemsResponse> = ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .body(itemService.list(circleId, currentUserProvider.currentUserId(), status, shop))

    @PostMapping("/items/{itemId}/claim")
    fun claim(
        @PathVariable itemId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
    ): ResponseEntity<ListItemResponse> = ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .body(itemService.claim(itemId, currentUserProvider.currentUserId(), idempotencyKey))

    @PostMapping("/items/{itemId}/status")
    fun updateStatus(
        @PathVariable itemId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("If-Match") expectedVersion: Long,
        @Valid @RequestBody request: UpdateListItemStatusRequest,
    ): ResponseEntity<ListItemResponse> = ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .body(itemService.markBought(itemId, currentUserProvider.currentUserId(), idempotencyKey, expectedVersion, request))

    @DeleteMapping("/items/{itemId}")
    fun delete(
        @PathVariable itemId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
    ): ResponseEntity<Void> {
        itemService.remove(itemId, currentUserProvider.currentUserId(), idempotencyKey)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }
}
