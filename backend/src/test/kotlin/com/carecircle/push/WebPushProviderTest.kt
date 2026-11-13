package com.carecircle.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class WebPushProviderTest {
    @Test
    fun `provider builds a VAPID signed encrypted request`() {
        val provider = WebPushProvider(
            "BGgL7I82SAQM78oyGwaJdrQFhVfZqL9h4Y18BLtgJQ-9pSGXwxqAWQudqmcv41RcWgk1ssUeItv4-8khxbhYveM=",
            "ANlfcVVFB4JiMYcI74_h9h04QZ1Ks96AyEa1yrMgDwn3",
            "mailto:test@example.invalid",
        )
        val target = PushTarget(
            UUID.randomUUID(),
            "https://fcm.googleapis.com/fcm/send/test-subscription",
            "BOtBVgsHVWXzwhDAoFE8P2IgQvabz_tuJjIlNacmS3XZ3fRDuVWiBp8bPR3vHCA78edquclcXXYb-olcj3QtIZ4=",
            "IOScBh9LW5mJ_K2JwXyNqQ==",
        )
        val notification = PushNotification(
            UUID.randomUUID(),
            "Care Circle",
            "A new urgent request needs attention.",
            "/circles/test?urgentRequestId=test",
        )

        val request = provider.preparePost(target, notification)

        assertTrue(request.getFirstHeader("Authorization").value.startsWith("vapid t="))
        assertEquals("aes128gcm", request.getFirstHeader("Content-Encoding").value)
        assertEquals("3600", request.getFirstHeader("TTL").value)
        assertTrue(request.entity.contentLength > 0)
    }

    @Test
    fun `successful push service response is accepted`() {
        assertEquals(PushSendResult.ACCEPTED, classifyWebPushResponse(201))
    }

    @Test
    fun `expired subscription responses are marked gone`() {
        assertEquals(PushSendResult.SUBSCRIPTION_GONE, classifyWebPushResponse(404))
        assertEquals(PushSendResult.SUBSCRIPTION_GONE, classifyWebPushResponse(410))
    }

    @Test
    fun `rate limits and server errors are retryable`() {
        listOf(408, 429, 500, 503).forEach { status ->
            val failure = assertThrows<PushDeliveryException> { classifyWebPushResponse(status) }
            assertTrue(failure.retryable)
            assertEquals("PUSH_HTTP_$status", failure.errorCode)
        }
    }

    @Test
    fun `other client errors are permanent`() {
        val failure = assertThrows<PushDeliveryException> { classifyWebPushResponse(403) }
        assertFalse(failure.retryable)
        assertEquals("PUSH_HTTP_403", failure.errorCode)
    }
}