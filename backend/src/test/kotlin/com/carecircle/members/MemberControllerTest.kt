package com.carecircle.members

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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Import(CircleControllerTest.TestAuthConfiguration::class)
class MemberControllerTest(
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
    fun `active members can view roster without private fields`() {
        mockMvc.get("/v1/circles/$circleId/members") {
            header("X-User-Id", familyId.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.circleName") { value("Care team") }
            jsonPath("$.currentUserRole") { value("FAMILY") }
            jsonPath("$.members.length()") { value(4) }
            jsonPath("$.members[0].displayName") { exists() }
            jsonPath("$.members[0].phone") { doesNotExist() }
        }
    }

    @Test
    fun `non-owner cannot remove a member`() {
        mockMvc.delete("/v1/circles/$circleId/members/$helperId") {
            header("X-User-Id", familyId.toString())
            header("Idempotency-Key", "member-remove-${UUID.randomUUID()}")
        }.andExpect { status { isForbidden() } }

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM membership_removal", Int::class.java))
    }

    @Test
    fun `owner removal is soft audited and idempotent`() {
        repeat(2) { attempt ->
            mockMvc.delete("/v1/circles/$circleId/members/$helperId") {
                header("Idempotency-Key", "remove-helper-$attempt-${UUID.randomUUID()}")
            }.andExpect {
                status { isNoContent() }
                header { string("Cache-Control", "no-store") }
            }
        }

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM membership_removal WHERE circle_id = ? AND user_id = ?", Int::class.java, circleId, helperId))
        assertEquals(ownerId, jdbc.queryForObject("SELECT removed_by FROM membership WHERE circle_id = ? AND user_id = ?", UUID::class.java, circleId, helperId))
        assertEquals(11L, jdbc.queryForObject("SELECT change_seq FROM circle WHERE id = ?", Long::class.java, circleId))
        mockMvc.get("/v1/circles/$circleId/members").andExpect {
            status { isOk() }
            jsonPath("$.members.length()") { value(3) }
        }
    }

    @Test
    fun `removed member loses roster access`() {
        mockMvc.delete("/v1/circles/$circleId/members/$familyId") {
            header("Idempotency-Key", "remove-family-${UUID.randomUUID()}")
        }.andExpect { status { isNoContent() } }

        mockMvc.get("/v1/circles/$circleId/members") {
            header("X-User-Id", familyId.toString())
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `owner cannot remove self or another owner`() {
        val secondOwnerId = UUID.randomUUID()
        addMember(secondOwnerId, "Second owner", "OWNER")

        mockMvc.delete("/v1/circles/$circleId/members/$ownerId") {
            header("Idempotency-Key", "remove-self-${UUID.randomUUID()}")
        }.andExpect { status { isConflict() } }
        mockMvc.delete("/v1/circles/$circleId/members/$secondOwnerId") {
            header("Idempotency-Key", "remove-owner-${UUID.randomUUID()}")
        }.andExpect { status { isConflict() } }

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM membership_removal", Int::class.java))
    }

    @Test
    fun `user outside circle cannot view its roster`() {
        mockMvc.get("/v1/circles/$circleId/members") {
            header("X-User-Id", UUID.randomUUID().toString())
        }.andExpect { status { isForbidden() } }
    }

    private fun addMember(userId: UUID, displayName: String, role: String) {
        jdbc.update("INSERT INTO user_profile (user_id, display_name) VALUES (?, ?)", userId, displayName)
        jdbc.update("INSERT INTO membership (circle_id, user_id, role) VALUES (?, ?, ?)", circleId, userId, role)
    }
}
