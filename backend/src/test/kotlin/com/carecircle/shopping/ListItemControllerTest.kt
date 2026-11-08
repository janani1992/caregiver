package com.carecircle.shopping

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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Import(CircleControllerTest.TestAuthConfiguration::class)
class ListItemControllerTest(
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
    fun `helpers can add claim and mark items bought but cannot delete`() {
        val itemId = UUID.randomUUID()
        createItem(itemId, helperId, "Milk")
            .andExpect {
                status { isCreated() }
                jsonPath("$.status") { value("NEEDED") }
                jsonPath("$.createdByName") { value("Siti") }
            }
        mockMvc.post("/v1/items/$itemId/claim") {
            header("X-User-Id", helperId.toString())
            header("Idempotency-Key", "claim-$itemId")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CLAIMED") }
            jsonPath("$.claimedBy") { value(helperId.toString()) }
        }
        mockMvc.post("/v1/items/$itemId/status") {
            header("X-User-Id", helperId.toString())
            header("Idempotency-Key", "bought-$itemId")
            header("If-Match", "2")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"status":"BOUGHT"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("BOUGHT") }
            jsonPath("$.version") { value(3) }
        }
        mockMvc.delete("/v1/items/$itemId") {
            header("X-User-Id", helperId.toString())
            header("Idempotency-Key", "delete-$itemId")
        }.andExpect { status { isForbidden() } }

        assertEquals(13L, jdbc.queryForObject("SELECT change_seq FROM circle WHERE id = ?", Long::class.java, circleId))
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM list_item_event WHERE item_id = ?", Int::class.java, itemId))
    }

    @Test
    fun `viewer can read but cannot add or mutate items`() {
        val itemId = UUID.randomUUID()
        createItem(itemId, ownerId, "Tea")
        mockMvc.get("/v1/circles/$circleId/items") {
            header("X-User-Id", viewerId.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.items[0].text") { value("Tea") }
        }
        createItem(UUID.randomUUID(), viewerId, "Forbidden")
            .andExpect { status { isForbidden() } }
        mockMvc.post("/v1/items/$itemId/claim") {
            header("X-User-Id", viewerId.toString())
            header("Idempotency-Key", "viewer-claim-$itemId")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `only family or owner can remove and deletion is soft`() {
        val itemId = UUID.randomUUID()
        createItem(itemId, ownerId, "Bread")
        mockMvc.delete("/v1/items/$itemId") {
            header("X-User-Id", familyId.toString())
            header("Idempotency-Key", "family-delete-$itemId")
        }.andExpect { status { isNoContent() } }
        mockMvc.get("/v1/circles/$circleId/items")
            .andExpect {
                status { isOk() }
                jsonPath("$.items.length()") { value(0) }
            }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM list_item WHERE id = ? AND removed_at IS NOT NULL AND removed_by = ?", Int::class.java, itemId, familyId))
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM list_item_event WHERE item_id = ?", Int::class.java, itemId))
    }

    @Test
    fun `creation retry is idempotent and changed body conflicts`() {
        val itemId = UUID.randomUUID()
        val key = "create-retry-$itemId"
        createItem(itemId, familyId, "Apples", key).andExpect { status { isCreated() } }
        createItem(itemId, familyId, "Apples", key).andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(itemId.toString()) }
        }
        createItem(itemId, familyId, "Oranges", key).andExpect { status { isConflict() } }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM list_item WHERE id = ?", Int::class.java, itemId))
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM list_item_event WHERE item_id = ?", Int::class.java, itemId))
    }

    @Test
    fun `stale bought version conflicts and removed membership loses access`() {
        val itemId = UUID.randomUUID()
        createItem(itemId, ownerId, "Rice")
        mockMvc.post("/v1/items/$itemId/claim") {
            header("Idempotency-Key", "owner-claim-$itemId")
        }.andExpect { status { isOk() } }
        mockMvc.post("/v1/items/$itemId/status") {
            header("Idempotency-Key", "stale-bought-$itemId")
            header("If-Match", "1")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"status":"BOUGHT"}"""
        }.andExpect { status { isConflict() } }

        jdbc.update("UPDATE membership SET removed_at = CURRENT_TIMESTAMP WHERE circle_id = ? AND user_id = ?", circleId, familyId)
        mockMvc.get("/v1/circles/$circleId/items") {
            header("X-User-Id", familyId.toString())
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `only one concurrent member can claim an item`() {
        val itemId = UUID.randomUUID()
        createItem(itemId, ownerId, "Bananas")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val requests = listOf(familyId, helperId).map { actorId ->
                Callable {
                    mockMvc.post("/v1/items/$itemId/claim") {
                        header("X-User-Id", actorId.toString())
                        header("Idempotency-Key", "concurrent-$actorId-$itemId")
                    }.andReturn().response.status
                }
            }
            val statuses = pool.invokeAll(requests).map { it.get() }.sorted()
            assertEquals(listOf(200, 409), statuses)
        } finally {
            pool.shutdownNow()
        }
        assertEquals(2, jdbc.queryForObject("SELECT version FROM list_item WHERE id = ?", Int::class.java, itemId))
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM list_item_event WHERE item_id = ?", Int::class.java, itemId))
    }

    @Test
    fun `item cannot be read or changed through another circle`() {
        val otherCircleId = UUID.randomUUID()
        val outsiderId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO circle (id, name, timezone, created_by, change_seq) VALUES (?, ?, ?, ?, 1)",
            otherCircleId,
            "Another circle",
            "Asia/Singapore",
            outsiderId,
        )
        jdbc.update("INSERT INTO user_profile (user_id, display_name) VALUES (?, ?)", outsiderId, "Other member")
        jdbc.update("INSERT INTO membership (circle_id, user_id, role) VALUES (?, ?, 'FAMILY')", otherCircleId, outsiderId)
        val itemId = UUID.randomUUID()
        createItem(itemId, ownerId, "Apples")

        mockMvc.get("/v1/circles/$otherCircleId/items") {
            header("X-User-Id", outsiderId.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(0) }
        }
        mockMvc.post("/v1/items/$itemId/claim") {
            header("X-User-Id", outsiderId.toString())
            header("Idempotency-Key", "cross-circle-$itemId")
        }.andExpect { status { isForbidden() } }
    }

    private fun createItem(
        itemId: UUID,
        actorId: UUID,
        text: String,
        idempotencyKey: String = "create-$itemId-${UUID.randomUUID()}",
    ) = mockMvc.post("/v1/circles/$circleId/items") {
        header("X-User-Id", actorId.toString())
        header("Idempotency-Key", idempotencyKey)
        contentType = org.springframework.http.MediaType.APPLICATION_JSON
        content = """{"id":"$itemId","text":"$text","qty":2,"unit":"packs","shopGroup":"Grocery"}"""
    }

    private fun addMember(userId: UUID, displayName: String, role: String) {
        jdbc.update("INSERT INTO user_profile (user_id, display_name) VALUES (?, ?)", userId, displayName)
        jdbc.update("INSERT INTO membership (circle_id, user_id, role) VALUES (?, ?, ?)", circleId, userId, role)
    }
}
