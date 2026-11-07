                                            package com.carecircle.invites

import com.carecircle.circles.CircleControllerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestConstructor
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post as postRequest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Import(CircleControllerTest.TestAuthConfiguration::class)
class InviteControllerTest(
    private val mockMvc: MockMvc,
    private val jdbc: JdbcTemplate,
) {
    private val ownerId = UUID.fromString("087a4d5a-9a7b-4ab1-b64f-7c7b00b3e104")
    private val circleId = UUID.fromString("10e1a98a-67a8-48ad-b065-5a226c3cdb24")

    @BeforeEach
    fun seedOwnerCircle() {
        jdbc.update("DELETE FROM circle_invite")
        jdbc.update("DELETE FROM idempotency_record")
        jdbc.update("DELETE FROM membership_removal")
        jdbc.update("DELETE FROM membership")
        jdbc.update("DELETE FROM circle")
        jdbc.update("DELETE FROM user_profile")
        jdbc.update(
            "INSERT INTO circle (id, name, timezone, created_by, change_seq) VALUES (?, ?, ?, ?, 0)",
            circleId,
            "The Tan family",
            "Asia/Singapore",
            ownerId,
        )
        jdbc.update("INSERT INTO user_profile (user_id, display_name) VALUES (?, ?)", ownerId, "Alex Tan")
        jdbc.update("INSERT INTO membership (circle_id, user_id, role) VALUES (?, ?, 'OWNER')", circleId, ownerId)
    }

    @Test
    fun `owner can create invite and same request retry returns same link`() {
        val inviteId = UUID.randomUUID()
        val token = secureToken()
        val key = "create-invite-${UUID.randomUUID()}"
        val body = inviteBody(inviteId, "FAMILY", token)

        repeat(2) {
            mockMvc.post("/v1/circles/$circleId/invites") {
                header("Idempotency-Key", key)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isCreated() }
                jsonPath("$.id") { value(inviteId.toString()) }
                jsonPath("$.role") { value("FAMILY") }
                jsonPath("$.inviteUrl") { value("http://localhost:5173/invites/$token") }
            }
        }

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM circle_invite WHERE id = ?", Int::class.java, inviteId))
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM circle_invite WHERE token_hash = ?", Int::class.java, token))
        assertEquals(1L, jdbc.queryForObject("SELECT change_seq FROM circle WHERE id = ?", Long::class.java, circleId))
    }

    @Test
    fun `non-owner cannot create an invite`() {
        val userId = UUID.randomUUID()
        mockMvc.post("/v1/circles/$circleId/invites") {
            header("X-User-Id", userId.toString())
            header("Idempotency-Key", "not-owner-${UUID.randomUUID()}")
            contentType = MediaType.APPLICATION_JSON
            content = inviteBody(UUID.randomUUID(), "HELPER", secureToken())
        }.andExpect { status { isForbidden() } }

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM circle_invite", Int::class.java))
    }

    @Test
    fun `owner role cannot be granted by an invite`() {
        mockMvc.post("/v1/circles/$circleId/invites") {
            header("Idempotency-Key", "owner-role-${UUID.randomUUID()}")
            contentType = MediaType.APPLICATION_JSON
            content = inviteBody(UUID.randomUUID(), "OWNER", secureToken())
        }.andExpect { status { isBadRequest() } }

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM circle_invite", Int::class.java))
    }

    @Test
    fun `preview reveals only circle and role before acceptance`() {
        val token = createInvite()
        mockMvc.get("/v1/invites/$token").andExpect {
            status { isOk() }
            jsonPath("$.circleId") { value(circleId.toString()) }
            jsonPath("$.circleName") { value("The Tan family") }
            jsonPath("$.role") { value("HELPER") }
            jsonPath("$.token") { doesNotExist() }
        }
    }

    @Test
    fun `same user can safely retry acceptance but another user cannot reuse invite`() {
        val token = createInvite("VIEWER")
        val memberId = UUID.randomUUID()
        repeat(2) { attempt ->
            mockMvc.post("/v1/invites/$token/accept") {
                header("X-User-Id", memberId.toString())
                header("Idempotency-Key", "accept-$attempt-${UUID.randomUUID()}")
                contentType = MediaType.APPLICATION_JSON
                content = acceptanceBody("Mina Tan")
            }.andExpect {
                status { isOk() }
                jsonPath("$.circleId") { value(circleId.toString()) }
                jsonPath("$.role") { value("VIEWER") }
                jsonPath("$.alreadyAccepted") { value(attempt == 1) }
            }
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM membership WHERE circle_id = ? AND user_id = ?", Int::class.java, circleId, memberId))
        assertEquals("Mina Tan", jdbc.queryForObject("SELECT display_name FROM user_profile WHERE user_id = ?", String::class.java, memberId))
        assertEquals(2L, jdbc.queryForObject("SELECT change_seq FROM circle WHERE id = ?", Long::class.java, circleId))

        mockMvc.post("/v1/invites/$token/accept") {
            header("X-User-Id", UUID.randomUUID().toString())
            header("Idempotency-Key", "replay-by-other-${UUID.randomUUID()}")
            contentType = MediaType.APPLICATION_JSON
            content = acceptanceBody("Other member")
        }.andExpect { status { isConflict() } }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM membership WHERE circle_id = ?", Int::class.java, circleId))
    }

    @Test
    fun `new invite reactivates a removed member and preserves removal audit`() {
        val token = createInvite("HELPER")
        val memberId = UUID.randomUUID()
        val removedAt = Instant.now().minusSeconds(120)
        jdbc.update("INSERT INTO user_profile (user_id, display_name) VALUES (?, ?)", memberId, "Former member")
        jdbc.update(
            "INSERT INTO membership (circle_id, user_id, role, removed_at, removed_by) VALUES (?, ?, 'FAMILY', ?, ?)",
            circleId,
            memberId,
            Timestamp.from(removedAt),
            ownerId,
        )
        jdbc.update(
            "INSERT INTO membership_removal (circle_id, user_id, removed_by, removed_at) VALUES (?, ?, ?, ?)",
            circleId,
            memberId,
            ownerId,
            Timestamp.from(removedAt),
        )

        mockMvc.post("/v1/invites/$token/accept") {
            header("X-User-Id", memberId.toString())
            header("Idempotency-Key", "rejoin-${UUID.randomUUID()}")
            contentType = MediaType.APPLICATION_JSON
            content = acceptanceBody("Rina Tan")
        }.andExpect {
            status { isOk() }
            jsonPath("$.role") { value("HELPER") }
            jsonPath("$.alreadyAccepted") { value(false) }
        }

        assertEquals("HELPER", jdbc.queryForObject("SELECT role FROM membership WHERE circle_id = ? AND user_id = ?", String::class.java, circleId, memberId))
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM membership WHERE circle_id = ? AND user_id = ? AND removed_at IS NOT NULL", Int::class.java, circleId, memberId))
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM membership_removal WHERE circle_id = ? AND user_id = ?", Int::class.java, circleId, memberId))
        assertEquals("Rina Tan", jdbc.queryForObject("SELECT display_name FROM user_profile WHERE user_id = ?", String::class.java, memberId))
    }

    @Test
    fun `concurrent acceptance by different users has exactly one winner`() {
        val token = createInvite()
        val users = listOf(UUID.randomUUID(), UUID.randomUUID())
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(users.size)
        try {
            val results = users.mapIndexed { index, userId ->
                executor.submit<Int> {
                    start.await()
                    mockMvc.perform(
                        postRequest("/v1/invites/$token/accept")
                            .header("X-User-Id", userId.toString())
                            .header("Idempotency-Key", "concurrent-${UUID.randomUUID()}")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(acceptanceBody("Member ${index + 1}")),
                    ).andReturn().response.status
                }
            }
            start.countDown()
            val statuses = results.map { it.get() }.sorted()
            assertEquals(listOf(200, 409), statuses)
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM membership WHERE circle_id = ?", Int::class.java, circleId))
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM circle_invite WHERE circle_id = ? AND accepted_at IS NOT NULL", Int::class.java, circleId))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `expired invite cannot be previewed or accepted`() {
        val token = createInvite()
        jdbc.update(
            "UPDATE circle_invite SET created_at = ?, expires_at = ? WHERE token_hash = ?",
            Timestamp.from(Instant.now().minusSeconds(8 * 24 * 60 * 60L)),
            Timestamp.from(Instant.now().minusSeconds(24 * 60 * 60L)),
            sha256(token),
        )

        mockMvc.get("/v1/invites/$token").andExpect { status { isGone() } }
        mockMvc.post("/v1/invites/$token/accept") {
            header("X-User-Id", UUID.randomUUID().toString())
            header("Idempotency-Key", "expired-${UUID.randomUUID()}")
            contentType = MediaType.APPLICATION_JSON
            content = acceptanceBody("Expired member")
        }.andExpect { status { isGone() } }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM membership WHERE circle_id = ?", Int::class.java, circleId))
    }

    @Test
    fun `invalid invite token returns not found`() {
        mockMvc.get("/v1/invites/not-a-token").andExpect { status { isNotFound() } }
    }

    private fun createInvite(role: String = "HELPER"): String {
        val token = secureToken()
        mockMvc.post("/v1/circles/$circleId/invites") {
            header("Idempotency-Key", "setup-${UUID.randomUUID()}")
            contentType = MediaType.APPLICATION_JSON
            content = inviteBody(UUID.randomUUID(), role, token)
        }.andExpect { status { isCreated() } }
        return token
    }

    private fun inviteBody(id: UUID, role: String, token: String) =
        """{"id":"$id","role":"$role","token":"$token"}"""

    private fun acceptanceBody(displayName: String) = """{"displayName":"$displayName"}"""

    private fun secureToken(): String = ByteArray(32).also(SecureRandom()::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
