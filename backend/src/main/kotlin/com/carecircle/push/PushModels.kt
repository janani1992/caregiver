package com.carecircle.push

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreatePushSubscriptionRequest(
    @field:NotBlank @field:Size(max = 2048) val endpoint: String,
    @field:Valid val keys: PushSubscriptionKeys,
)

data class PushSubscriptionKeys(
    @field:NotBlank @field:Size(max = 256) val p256dh: String,
    @field:NotBlank @field:Size(max = 256) val auth: String,
)

data class PushConfigurationResponse(val publicKey: String?, val deliveryEnabled: Boolean)

data class PushSubscriptionResponse(
    val id: UUID,
    val endpoint: String,
    val createdAt: Instant,
)

data class PushSubscriptionsResponse(val subscriptions: List<PushSubscriptionResponse>)

data class NotificationDeliveryResponse(
    val id: UUID,
    val recipientId: UUID,
    val recipientName: String,
    val status: NotificationDeliveryStatus,
    val attempts: Int,
    val acceptedAt: Instant?,
)

data class NotificationDeliveriesResponse(
    val requestId: UUID,
    val deliveries: List<NotificationDeliveryResponse>,
)

enum class NotificationDeliveryStatus {
    PENDING,
    PROCESSING,
    ACCEPTED,
    SIMULATED,
    FAILED,
    CANCELLED,
}

class PushSubscriptionForbiddenException : RuntimeException("Only active circle members can register push subscriptions")
class PushSubscriptionNotFoundException : RuntimeException("Push subscription not found")
class PushSubscriptionConflictException(message: String) : RuntimeException(message)
class PushSubscriptionInvalidRequestException(message: String) : RuntimeException(message)
