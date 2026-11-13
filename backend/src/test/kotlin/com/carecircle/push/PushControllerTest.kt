package com.carecircle.push

import com.carecircle.circles.CircleControllerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestConstructor
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Import(CircleControllerTest.TestAuthConfiguration::class, PushTestConfiguration::class)
class PushControllerTest(
    private val mockMvc: MockMvc,
    private val jdbc: JdbcTemplate,
    private val testPushProvider: TestPushProvider,
) {
    @Autowired
    private lateinit var dispatcher: NotificationOutboxDispatcher

    private val ownerId = UUID.fromString("087a4d5a-9a7b-4ab1-b64f-7c7b00b3e104")
    private val circleId = UUID.fromString("8d72daaf-c76f-4be6-b56e-c2732604fe74")
    private val helperId = UUID.fromString("fc700cd8-d169-4104-a655-581bcdde920d")

    @BeforeEach
    fun seedMembers() {
        testPushProvider.reset()
        jdbc.update("DELETE FROM notification_outbox")
        jdbc.update("DELETE FROM push_subscription")
        jdbc.update("DELETE FROM urgent_request_event")
        jdbc.update("DELETE FROM urgent_request")
        jdbc.update("DELETE FROM list_item_event")
        jdbc.update("DELETE FROM list_item")
        jdbc.update("DELETE FROM circle_invite")
        jdbc.update("DELETE FROM idempotency_record")
        jdbc.update("DELETE FROM membership_removal")
        jdbc.update("DELETE FROM membership")
        jdbc.update("DELETE FROM circle")
        jdbc.update("DELETE FROM user_profile")
        jdbc.update(
            "INSERT INTO circle (id, name, timezone, created_by, change_seq) VALUES (?, ?, ?, ?, 1)",
            circleId,
            "Care team",
            "Asia/Singapore",
            ownerId,
        )
        addMember(ownerId, "Alex", "OWNER")
        addMember(helperId, "Siti", "HELPER")
    }

    @Test
    fun `member can register list and remove their own encrypted subscription`() {
        val endpoint = "https://fcm.googleapis.com/subscription/${UUID.randomUUID()}"
        repeat(2) { attempt ->
            mockMvc.post("/v1/push/subscriptions/me") {
                header("X-User-Id", helperId.toString())
                contentType = org.springframework.http.MediaType.APPLICATION_JSON
                content = subscriptionBody(endpoint, "public-key-$attempt", "auth-secret-$attempt")
            }.andExpect {
                status { isCreated() }
                jsonPath("$.endpoint") { value(endpoint) }
            }
        }
        val endpointHash = endpointHash(endpoint)
        val subscriptionId = jdbc.queryForObject("SELECT id FROM push_subscription WHERE endpoint_hash = ?", UUID::class.java, endpointHash)
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM push_subscription WHERE user_id = ?", Int::class.java, helperId))
        assertNotEquals(
            "auth-secret-1",
            jdbc.queryForObject("SELECT auth_secret FROM push_subscription WHERE endpoint_hash = ?", String::class.java, endpointHash),
        )
        assertNotEquals(
            endpoint,
            jdbc.queryForObject("SELECT endpoint_ciphertext FROM push_subscription WHERE endpoint_hash = ?", String::class.java, endpointHash),
        )

        mockMvc.get("/v1/push/subscriptions/me") {
            header("X-User-Id", helperId.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.subscriptions.length()") { value(1) }
            jsonPath("$.subscriptions[0].keys") { doesNotExist() }
        }
        mockMvc.delete("/v1/push/subscriptions/me/$subscriptionId") {
            header("X-User-Id", helperId.toString())
        }.andExpect { status { isNoContent() } }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM push_subscription WHERE user_id = ? AND disabled_at IS NULL", Int::class.java, helperId))
    }

    @Test
    fun `subscription endpoint cannot be taken over by another member`() {
        val endpoint = "https://fcm.googleapis.com/owned/${UUID.randomUUID()}"
        register(helperId, endpoint)
        mockMvc.post("/v1/push/subscriptions/me") {
            header("X-User-Id", ownerId.toString())
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = subscriptionBody(endpoint, "other-public", "other-auth")
        }.andExpect { status { isConflict() } }
        assertEquals(helperId, jdbc.queryForObject("SELECT user_id FROM push_subscription WHERE endpoint_hash = ?", UUID::class.java, endpointHash(endpoint)))
    }

    @Test
    fun `member cannot remove another account subscription`() {
        val endpoint = "https://fcm.googleapis.com/private-${UUID.randomUUID()}"
        register(helperId, endpoint)
        val subscriptionId = jdbc.queryForObject("SELECT id FROM push_subscription WHERE endpoint_hash = ?", UUID::class.java, endpointHash(endpoint))

        mockMvc.delete("/v1/push/subscriptions/me/$subscriptionId") {
            header("X-User-Id", ownerId.toString())
        }.andExpect { status { isNotFound() } }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM push_subscription WHERE id = ? AND disabled_at IS NOT NULL", Int::class.java, subscriptionId))
    }

    @Test
    fun `removed member and non-https endpoints cannot register`() {
        jdbc.update("UPDATE membership SET removed_at = CURRENT_TIMESTAMP WHERE circle_id = ? AND user_id = ?", circleId, helperId)
        mockMvc.post("/v1/push/subscriptions/me") {
            header("X-User-Id", helperId.toString())
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = subscriptionBody("https://fcm.googleapis.com/removed", "public-key", "auth-secret")
        }.andExpect { status { isForbidden() } }
        mockMvc.post("/v1/push/subscriptions/me") {
            header("X-User-Id", ownerId.toString())
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = subscriptionBody("http://fcm.googleapis.com/not-secure", "public-key", "auth-secret")
        }.andExpect { status { isBadRequest() } }
        mockMvc.post("/v1/push/subscriptions/me") {
            header("X-User-Id", ownerId.toString())
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = subscriptionBody("https://internal.example.test/metadata", "public-key", "auth-secret")
        }.andExpect { status { isBadRequest() } }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM push_subscription", Int::class.java))
    }

    @Test
    fun `dispatcher retries transient errors then accepts stable generic payload`() {
        val endpoint = "https://fcm.googleapis.com/retry-${UUID.randomUUID()}"
        register(helperId, endpoint)
        val requestId = UUID.randomUUID()
        mockMvc.post("/v1/circles/$circleId/urgent") {
            header("X-User-Id", ownerId.toString())
            header("Idempotency-Key", "urgent-$requestId")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"id":"$requestId","text":"Private family health details"}"""
        }.andExpect { status { isCreated() } }

        var call = 0
        testPushProvider.behavior = { _, _ ->
            call += 1
            if (call == 1) throw IllegalStateException("temporary provider outage")
            PushSendResult.ACCEPTED
        }
        assertEquals(true, dispatcher.dispatchOne())
        assertEquals(
            "PENDING",
            jdbc.queryForObject("SELECT status FROM notification_outbox WHERE request_id = ?", String::class.java, requestId),
        )
        assertEquals(
            "IllegalStateException",
            jdbc.queryForObject("SELECT last_error_code FROM notification_outbox WHERE request_id = ?", String::class.java, requestId),
        )
        jdbc.update("UPDATE notification_outbox SET next_attempt_at = CURRENT_TIMESTAMP WHERE request_id = ?", requestId)

        assertEquals(true, dispatcher.dispatchOne())
        assertEquals(
            "ACCEPTED",
            jdbc.queryForObject("SELECT status FROM notification_outbox WHERE request_id = ?", String::class.java, requestId),
        )
        assertEquals(2, jdbc.queryForObject("SELECT attempts FROM notification_outbox WHERE request_id = ?", Int::class.java, requestId))
        val payload = testPushProvider.sentNotifications.last()
        assertEquals("A new urgent request needs attention.", payload.body)
        assertEquals("Care Circle", payload.title)
        assertEquals("/circles/$circleId?urgentRequestId=$requestId", payload.url)
    }

    @Test
    fun `provider gone response disables the stale subscription`() {
        val endpoint = "https://fcm.googleapis.com/gone-${UUID.randomUUID()}"
        register(helperId, endpoint)
        val requestId = UUID.randomUUID()
        mockMvc.post("/v1/circles/$circleId/urgent") {
            header("X-User-Id", ownerId.toString())
            header("Idempotency-Key", "urgent-gone-$requestId")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"id":"$requestId","text":"Check delivery"}"""
        }.andExpect { status { isCreated() } }
        testPushProvider.behavior = { _, _ -> PushSendResult.SUBSCRIPTION_GONE }

        dispatcher.dispatchOne()

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM push_subscription WHERE endpoint_hash = ? AND disabled_at IS NOT NULL", Int::class.java, endpointHash(endpoint)))
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM notification_outbox WHERE request_id = ?", String::class.java, requestId))
    }

    private fun register(userId: UUID, endpoint: String) {
        mockMvc.post("/v1/push/subscriptions/me") {
            header("X-User-Id", userId.toString())
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = subscriptionBody(endpoint, "public-key", "auth-secret")
        }.andExpect { status { isCreated() } }
    }

    private fun subscriptionBody(endpoint: String, p256dh: String, auth: String) =
        """{"endpoint":"$endpoint","keys":{"p256dh":"$p256dh","auth":"$auth"}}"""

    private fun endpointHash(endpoint: String): String = MessageDigest.getInstance("SHA-256")
        .digest(endpoint.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun addMember(userId: UUID, displayName: String, role: String) {
        jdbc.update("INSERT INTO user_profile (user_id, display_name) VALUES (?, ?)", userId, displayName)
        jdbc.update("INSERT INTO membership (circle_id, user_id, role) VALUES (?, ?, ?)", circleId, userId, role)
    }
}

@TestConfiguration
class PushTestConfiguration {
    @Bean
    @Primary
    fun testPushProvider() = TestPushProvider()
}

class TestPushProvider : PushProvider {
    var behavior: (PushTarget, PushNotification) -> PushSendResult = { _, _ -> PushSendResult.ACCEPTED }
    val sentNotifications = mutableListOf<PushNotification>()

    override fun send(target: PushTarget, notification: PushNotification): PushSendResult {
        sentNotifications.add(notification)
        return behavior(target, notification)
    }

    fun reset() {
        behavior = { _, _ -> PushSendResult.ACCEPTED }
        sentNotifications.clear()
    }
}
