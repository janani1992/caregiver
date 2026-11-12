package com.carecircle.urgent

import com.carecircle.circles.CircleControllerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestConstructor
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Import(CircleControllerTest.TestAuthConfiguration::class)
class UrgentRequestControllerTest(
    private val mockMvc: MockMvc,
    private val jdbc: JdbcTemplate,
) {
    private val ownerId = UUID.fromString("087a4d5a-9a7b-4ab1-b64f-7c7b00b3e104")
    private val circleId = UUID.fromString("8d72daaf-c76f-4be6-b56e-c2732604fe74")
    private val familyId = UUID.fromString("f18d2089-f2ca-412e-a129-d034b3841554")
    private val helperId = UUID.fromString("fc700cd8-d169-4104-a655-581bcdde920d")
    private val viewerId = UUID.fromString("b8f3e7e1-2be4-47f3-8c43-3931a3e6ab9a")

    @BeforeEach
    fun seedMembers() {
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
            "INSERT INTO circle (id, name, timezone, created_by, change_seq) VALUES (?, ?, ?, ?, 10)",
            circleId,
            "Care team",
            "Asia/Singapore",
            ownerId,
        )
        addMember(ownerId, "Alex Tan", "OWNER")
        addMember(familyId, "Rina Tan", "FAMILY")
        addMember(helperId, "Siti", "HELPER")
        addMember(viewerId, "Jamie", "VIEWER")
    }

    @Test
    fun `only owner or family can create urgent requests`() {
        val requestId = UUID.randomUUID()
        createRequest(requestId, ownerId, "Please help Dad with his medicine")
            .andExpect {
                status { isCreated() }
                jsonPath("$.state") { value("OPEN") }
                jsonPath("$.senderName") { value("Alex Tan") }
            }
        createRequest(UUID.randomUUID(), helperId, "Helper-created request")
            .andExpect { status { isForbidden() } }
        createRequest(UUID.randomUUID(), viewerId, "Viewer-created request")
            .andExpect { status { isForbidden() } }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM urgent_request", Int::class.java))
    }

    @Test
    fun `any active member can mark seen and a helper can take responsibility`() {
        val requestId = UUID.randomUUID()
        createRequest(requestId, familyId, "Please check the front door")

        mockMvc.post("/v1/urgent/$requestId/ack") {
            header("X-User-Id", viewerId.toString())
            header("Idempotency-Key", "seen-$requestId")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"acknowledgement":"SEEN"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.state") { value("OPEN") }
            jsonPath("$.seenByMe") { value(true) }
        }
        mockMvc.get("/v1/circles/$circleId/urgent") {
            header("X-User-Id", helperId.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.requests[0].seenByMe") { value(false) }
        }
        mockMvc.post("/v1/urgent/$requestId/ack") {
            header("X-User-Id", helperId.toString())
            header("Idempotency-Key", "on-it-$requestId")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"acknowledgement":"ON_IT"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.state") { value("ON_IT") }
            jsonPath("$.handledBy") { value(helperId.toString()) }
            jsonPath("$.handledByName") { value("Siti") }
        }
        assertEquals(13L, jdbc.queryForObject("SELECT change_seq FROM circle WHERE id = ?", Long::class.java, circleId))
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM urgent_request_event WHERE request_id = ?", Int::class.java, requestId))
    }

    @Test
    fun `viewer can mark seen but cannot take or resolve a request`() {
        val requestId = UUID.randomUUID()
        createRequest(requestId, ownerId, "Please bring water")
        mockMvc.post("/v1/urgent/$requestId/ack") {
            header("X-User-Id", viewerId.toString())
            header("Idempotency-Key", "viewer-on-it-$requestId")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"acknowledgement":"ON_IT"}"""
        }.andExpect { status { isForbidden() } }
        mockMvc.post("/v1/urgent/$requestId/resolve") {
            header("X-User-Id", viewerId.toString())
            header("Idempotency-Key", "viewer-resolve-$requestId")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `only one active member can take an open request`() {
        val requestId = UUID.randomUUID()
        createRequest(requestId, ownerId, "Urgent groceries")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val requests = listOf(familyId, helperId).map { actorId ->
                Callable {
                    mockMvc.post("/v1/urgent/$requestId/ack") {
                        header("X-User-Id", actorId.toString())
                        header("Idempotency-Key", "race-$actorId-$requestId")
                        contentType = org.springframework.http.MediaType.APPLICATION_JSON
                        content = """{"acknowledgement":"ON_IT"}"""
                    }.andReturn().response.status
                }
            }
            assertEquals(listOf(200, 409), pool.invokeAll(requests).map { it.get() }.sorted())
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM urgent_request_event WHERE request_id = ? AND event_type = 'ON_IT'", Int::class.java, requestId))
    }

    @Test
    fun `handler can resolve and retries do not duplicate events`() {
        val requestId = UUID.randomUUID()
        val key = "create-$requestId"
        repeat(2) { createRequest(requestId, ownerId, "Door is unlocked", key).andExpect { status { isCreated() } } }
        mockMvc.post("/v1/urgent/$requestId/ack") {
            header("X-User-Id", helperId.toString())
            header("Idempotency-Key", "take-$requestId")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"acknowledgement":"ON_IT"}"""
        }.andExpect { status { isOk() } }
        repeat(2) {
            mockMvc.post("/v1/urgent/$requestId/resolve") {
                header("X-User-Id", helperId.toString())
                header("Idempotency-Key", "resolve-$requestId")
            }.andExpect {
                status { isOk() }
                jsonPath("$.state") { value("RESOLVED") }
                jsonPath("$.resolvedBy") { value(helperId.toString()) }
            }
        }
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM urgent_request_event WHERE request_id = ?", Int::class.java, requestId))
        assertEquals(13L, jdbc.queryForObject("SELECT change_seq FROM circle WHERE id = ?", Long::class.java, circleId))
    }

    @Test
    fun `request from a removed member is not accessible to them`() {
        val requestId = UUID.randomUUID()
        createRequest(requestId, familyId, "Please check the lights")
        jdbc.update("UPDATE membership SET removed_at = CURRENT_TIMESTAMP WHERE circle_id = ? AND user_id = ?", circleId, familyId)
        mockMvc.get("/v1/circles/$circleId/urgent") {
            header("X-User-Id", familyId.toString())
        }.andExpect { status { isForbidden() } }
        mockMvc.post("/v1/urgent/$requestId/ack") {
            header("X-User-Id", familyId.toString())
            header("Idempotency-Key", "removed-seen-$requestId")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"acknowledgement":"SEEN"}"""
        }.andExpect { status { isForbidden() } }
    }

    private fun createRequest(
        requestId: UUID,
        actorId: UUID,
        text: String,
        idempotencyKey: String = "create-$requestId-${UUID.randomUUID()}",
    ) = mockMvc.post("/v1/circles/$circleId/urgent") {
        header("X-User-Id", actorId.toString())
        header("Idempotency-Key", idempotencyKey)
        contentType = org.springframework.http.MediaType.APPLICATION_JSON
        content = """{"id":"$requestId","text":"$text"}"""
    }

    private fun addMember(userId: UUID, displayName: String, role: String) {
        jdbc.update("INSERT INTO user_profile (user_id, display_name) VALUES (?, ?)", userId, displayName)
        jdbc.update("INSERT INTO membership (circle_id, user_id, role) VALUES (?, ?, ?)", circleId, userId, role)
    }
}
