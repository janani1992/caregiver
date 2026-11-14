package com.carecircle.carelog

import com.fasterxml.jackson.databind.ObjectMapper
import com.carecircle.circles.CircleControllerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Import(CircleControllerTest.TestAuthConfiguration::class)
class CareLogControllerTest(
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
        jdbc.update("DELETE FROM care_log_entry")
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
    fun `helpers can create encrypted append only entries and retries are idempotent`() {
        val entryId = UUID.randomUUID()
        val key = "care-log-$entryId"
        createEntry(entryId, helperId, "Medicine taken after breakfast", "MEDICATION", key).andExpect {
            status { isCreated() }
            jsonPath("$.text") { value("Medicine taken after breakfast") }
            jsonPath("$.category") { value("MEDICATION") }
            jsonPath("$.authorName") { value("Siti") }
            jsonPath("$.createdAt") { exists() }
        }
        createEntry(entryId, helperId, "Medicine taken after breakfast", "MEDICATION", key).andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(entryId.toString()) }
        }
        createEntry(entryId, helperId, "A different update", "MEDICATION", key)
            .andExpect { status { isConflict() } }

        val storedBody = jdbc.queryForObject("SELECT body_ciphertext FROM care_log_entry WHERE id = ?", String::class.java, entryId)
        assertNotEquals("Medicine taken after breakfast", storedBody)
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM care_log_entry WHERE id = ?", Int::class.java, entryId))
        assertEquals(11L, jdbc.queryForObject("SELECT change_seq FROM circle WHERE id = ?", Long::class.java, circleId))
    }

    @Test
    fun `viewers can read entries but cannot add and removed members lose access`() {
        val entryId = UUID.randomUUID()
        createEntry(entryId, ownerId, "A calm afternoon walk", "WELLBEING").andExpect { status { isCreated() } }

        mockMvc.get("/v1/circles/$circleId/log") {
            header("X-User-Id", viewerId.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.entries.length()") { value(1) }
            jsonPath("$.entries[0].text") { value("A calm afternoon walk") }
        }
        createEntry(UUID.randomUUID(), viewerId, "Viewer entry", "GENERAL")
            .andExpect { status { isForbidden() } }

        jdbc.update("UPDATE membership SET removed_at = CURRENT_TIMESTAMP WHERE circle_id = ? AND user_id = ?", circleId, familyId)
        mockMvc.get("/v1/circles/$circleId/log") {
            header("X-User-Id", familyId.toString())
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `cursor pages are newest first and do not duplicate entries`() {
        val ids = listOf(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            UUID.fromString("00000000-0000-0000-0000-000000000003"),
        )
        ids.forEachIndexed { index, id -> createEntry(id, ownerId, "Update $index", "GENERAL") }
        ids.forEach { id ->
            jdbc.update(
                "UPDATE care_log_entry SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-11-14T10:00:00Z")),
                id,
            )
        }

        val firstPage = mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/circles/$circleId/log?limit=2")
                .header("X-User-Id", familyId.toString()),
        ).andReturn()
        assertEquals(200, firstPage.response.status)
        val firstPageJson = ObjectMapper().readTree(firstPage.response.contentAsString)
        assertEquals(ids[2].toString(), firstPageJson.get("entries").get(0).get("id").asText())
        assertEquals(ids[1].toString(), firstPageJson.get("entries").get(1).get("id").asText())
        val cursor = firstPageJson.get("nextCursor").asText()

        mockMvc.get("/v1/circles/$circleId/log?limit=2&cursor=$cursor") {
            header("X-User-Id", familyId.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.entries.length()") { value(1) }
            jsonPath("$.entries[0].id") { value(ids[0].toString()) }
            jsonPath("$.nextCursor") { isEmpty() }
        }
    }

    @Test
    fun `invalid limits cursors and blank entry text are rejected`() {
        mockMvc.get("/v1/circles/$circleId/log?limit=51") {
            header("X-User-Id", ownerId.toString())
        }.andExpect { status { isBadRequest() } }
        mockMvc.get("/v1/circles/$circleId/log?cursor=not-a-cursor") {
            header("X-User-Id", ownerId.toString())
        }.andExpect { status { isBadRequest() } }
        createEntry(UUID.randomUUID(), ownerId, "   ", "GENERAL")
            .andExpect { status { isBadRequest() } }
    }

    private fun createEntry(
        entryId: UUID,
        actorId: UUID,
        text: String,
        category: String,
        idempotencyKey: String = "create-$entryId-${UUID.randomUUID()}",
    ) = mockMvc.post("/v1/circles/$circleId/log") {
        header("X-User-Id", actorId.toString())
        header("Idempotency-Key", idempotencyKey)
        contentType = org.springframework.http.MediaType.APPLICATION_JSON
        content = """{"id":"$entryId","text":"$text","category":"$category"}"""
    }

    private fun addMember(userId: UUID, displayName: String, role: String) {
        jdbc.update("INSERT INTO user_profile (user_id, display_name) VALUES (?, ?)", userId, displayName)
        jdbc.update("INSERT INTO membership (circle_id, user_id, role) VALUES (?, ?, ?)", circleId, userId, role)
    }
}