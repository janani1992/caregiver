package com.carecircle.push

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.bouncycastle.jce.provider.BouncyCastleProvider
import nl.martijndwars.webpush.Notification
import nl.martijndwars.webpush.Encoding
import nl.martijndwars.webpush.PushService
import java.security.Security
import java.util.UUID

interface PushProvider {
    fun send(target: PushTarget, notification: PushNotification): PushSendResult
}

data class PushTarget(
    val subscriptionId: UUID,
    val endpoint: String,
    val p256dhKey: String,
    val authSecret: String,
)

data class PushNotification(
    val notificationId: UUID,
    val title: String,
    val body: String,
    val url: String,
)

enum class PushSendResult {
    ACCEPTED,
    SIMULATED,
    SUBSCRIPTION_GONE,
}

internal fun classifyWebPushResponse(status: Int): PushSendResult = when (status) {
    in 200..299 -> PushSendResult.ACCEPTED
    404, 410 -> PushSendResult.SUBSCRIPTION_GONE
    else -> throw PushDeliveryException(
        "Web Push service returned HTTP $status",
        retryable = status == 408 || status == 429 || status >= 500,
        errorCode = "PUSH_HTTP_$status",
    )
}

class PushDeliveryException(
    message: String,
    val retryable: Boolean,
    val errorCode: String = "PUSH_FAILURE",
) : RuntimeException(message)

@Component
@ConditionalOnProperty(name = ["care-circle.push.provider"], havingValue = "webpush")
class WebPushProvider(
    @Value("\${care-circle.push.vapid-public-key}") publicKey: String,
    @Value("\${care-circle.push.vapid-private-key}") privateKey: String,
    @Value("\${care-circle.push.vapid-subject}") subject: String,
) : PushProvider {
    private val pushService: PushService

    init {
        require(publicKey.isNotBlank()) { "PUSH_VAPID_PUBLIC_KEY is required for Web Push" }
        require(privateKey.isNotBlank()) { "PUSH_VAPID_PRIVATE_KEY is required for Web Push" }
        require(subject.isNotBlank()) { "PUSH_VAPID_SUBJECT is required for Web Push" }
        Security.addProvider(BouncyCastleProvider())
        pushService = try {
            PushService(publicKey, privateKey, subject)
        } catch (exception: Exception) {
            throw IllegalStateException("Invalid Web Push VAPID configuration", exception)
        }
    }

    override fun send(target: PushTarget, notification: PushNotification): PushSendResult {
        val webPushNotification = createNotification(target, notification)

        val response = try {
            pushService.send(webPushNotification)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PushDeliveryException("Web Push request was interrupted", retryable = true, errorCode = "PUSH_INTERRUPTED")
        } catch (exception: Exception) {
            throw PushDeliveryException("Web Push request failed", retryable = true, errorCode = "PUSH_REQUEST_FAILED")
        }

        return classifyWebPushResponse(response.statusLine.statusCode)
    }

    internal fun preparePost(target: PushTarget, notification: PushNotification) =
        pushService.preparePost(createNotification(target, notification), Encoding.AES128GCM)

    private fun createNotification(target: PushTarget, notification: PushNotification): Notification = try {
        Notification(
            target.endpoint,
            target.p256dhKey,
            target.authSecret,
            """{"title":"${jsonEscape(notification.title)}","body":"${jsonEscape(notification.body)}","url":"${jsonEscape(notification.url)}","notificationId":"${notification.notificationId}"}""".toByteArray(Charsets.UTF_8),
            TTL_SECONDS,
        )
    } catch (exception: Exception) {
        throw PushDeliveryException(
            "Invalid browser push subscription",
            retryable = false,
            errorCode = "INVALID_SUBSCRIPTION",
        )
    }

    private fun jsonEscape(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
    }

    private companion object {
        const val TTL_SECONDS = 3600
    }
}

@Component
@ConditionalOnProperty(name = ["care-circle.push.provider"], havingValue = "log", matchIfMissing = true)
class DevelopmentLoggingPushProvider : PushProvider {
    override fun send(target: PushTarget, notification: PushNotification): PushSendResult {
        logger.info(
            "Development push accepted: notificationId={}, subscriptionId={}, title={}, url={}",
            notification.notificationId,
            target.subscriptionId,
            notification.title,
            notification.url,
        )
        return PushSendResult.SIMULATED
    }

    private companion object {
        val logger = LoggerFactory.getLogger(DevelopmentLoggingPushProvider::class.java)
    }
}
