package com.carecircle.push

import com.carecircle.auth.CurrentUserProvider
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/v1/push")
class PushController(
    private val subscriptionService: PushSubscriptionService,
    private val deliveryService: NotificationDeliveryService,
    private val currentUserProvider: CurrentUserProvider,
) {
    @GetMapping("/configuration")
    fun configuration(): ResponseEntity<PushConfigurationResponse> = ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .body(subscriptionService.configuration())

    @GetMapping("/subscriptions/me")
    fun listMine(): ResponseEntity<PushSubscriptionsResponse> = ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .body(subscriptionService.list(currentUserProvider.currentUserId()))

    @PostMapping("/subscriptions/me")
    fun register(
        @Valid @RequestBody request: CreatePushSubscriptionRequest,
    ): ResponseEntity<PushSubscriptionResponse> = ResponseEntity
        .status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .body(subscriptionService.register(currentUserProvider.currentUserId(), request))

    @DeleteMapping("/subscriptions/me/{subscriptionId}")
    fun remove(@PathVariable subscriptionId: UUID): ResponseEntity<Void> {
        subscriptionService.remove(currentUserProvider.currentUserId(), subscriptionId)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    @GetMapping("/urgent/{requestId}/deliveries")
    fun deliveries(@PathVariable requestId: UUID): ResponseEntity<NotificationDeliveriesResponse> = ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .body(deliveryService.list(requestId, currentUserProvider.currentUserId()))
}
