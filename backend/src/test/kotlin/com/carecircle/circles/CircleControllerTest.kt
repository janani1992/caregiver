package com.carecircle.circles

import com.carecircle.auth.CurrentUserProvider
import jakarta.servlet.ServletException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestConstructor
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Import(CircleControllerTest.TestAuthConfiguration::class)
class CircleControllerTest(
    private val mockMvc: MockMvc,
    private val jdbc: JdbcTemplate,
) {
    private val ownerId = UUID.fromString("087a4d5a-9a7b-4ab1-b64f-7c7b00b3e104")

    @BeforeEach
    fun clearDatabase() {
        jdbc.update("DELETE FROM idempotency_record")
        jdbc.update("DELETE FROM membership_removal")
        jdbc.update("DELETE FROM membership")
        jdbc.update("DELETE FROM circle")
        jdbc.update("DELETE FROM user_profile")
    }

    @Test
    fun `creates circle and owner membership atomically`() {
        val circleId = UUID.randomUUID()
        mockMvc.post("/v1/circles") {
            header("Idempotency-Key", "create-$circleId")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"id":"$circleId","displayName":"Alex Tan","name":"  Care Team  ","timezone":"Asia/Singapore"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(circleId.toString()) }
            jsonPath("$.name") { value("Care Team") }
            jsonPath("$.role") { value("OWNER") }
            jsonPath("$.changeSeq") { value(1) }
        }

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM circle WHERE id = ?", Int::class.java, circleId))
        assertEquals(
            "OWNER",
            jdbc.queryForObject("SELECT role FROM membership WHERE circle_id = ? AND user_id = ?", String::class.java, circleId, ownerId),
        )
        assertEquals("Alex Tan", jdbc.queryForObject("SELECT display_name FROM user_profile WHERE user_id = ?", String::class.java, ownerId))
    }

    @Test
    fun `rolls back circle when owner membership insert fails`() {
        val circleId = UUID.randomUUID()
        jdbc.execute(
            "CREATE TRIGGER fail_membership BEFORE INSERT ON membership FOR EACH ROW " +
                "CALL 'com.carecircle.circles.FailingMembershipInsertTrigger'",
        )
        try {
            assertThrows(ServletException::class.java) {
                mockMvc.post("/v1/circles") {
                    header("Idempotency-Key", "rollback-$circleId")
                    contentType = org.springframework.http.MediaType.APPLICATION_JSON
                    content = """{"id":"$circleId","displayName":"Alex Tan","name":"Family","timezone":"Asia/Singapore"}"""
                }
            }
        } finally {
            jdbc.execute("DROP TRIGGER fail_membership")
        }

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM circle WHERE id = ?", Int::class.java, circleId))
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = ?", Int::class.java, "rollback-$circleId"))
    }

    @Test
    fun `replaying idempotency key returns original circle without duplicate rows`() {
        val circleId = UUID.randomUUID()
        val key = "retry-$circleId"
        val body = """{"id":"$circleId","displayName":"Alex Tan","name":"Family","timezone":"Asia/Singapore"}"""
        repeat(2) {
            mockMvc.post("/v1/circles") {
                header("Idempotency-Key", key)
                contentType = org.springframework.http.MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isCreated() }
                jsonPath("$.id") { value(circleId.toString()) }
            }
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM circle WHERE id = ?", Int::class.java, circleId))
    }

    @Test
    fun `reusing key with different request returns conflict`() {
        val circleId = UUID.randomUUID()
        mockMvc.post("/v1/circles") {
            header("Idempotency-Key", "same-key-$circleId")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"id":"$circleId","displayName":"Alex Tan","name":"Family","timezone":"Asia/Singapore"}"""
        }.andExpect { status { isCreated() } }

        mockMvc.post("/v1/circles") {
            header("Idempotency-Key", "same-key-$circleId")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"id":"${UUID.randomUUID()}","displayName":"Alex Tan","name":"Different","timezone":"Asia/Singapore"}"""
        }.andExpect { status { isConflict() } }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM circle", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = ?", Int::class.java, "same-key-$circleId"))
    }

    @Test
    fun `rejects invalid timezone`() {
        mockMvc.post("/v1/circles") {
            header("Idempotency-Key", "bad-timezone-${UUID.randomUUID()}")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"id":"${UUID.randomUUID()}","displayName":"Alex Tan","name":"Family","timezone":"Mars/Olympus"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @TestConfiguration
    class TestAuthConfiguration {
        @Bean
        fun currentUserProvider(): CurrentUserProvider = CurrentUserProvider {
            val request = RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes
            request.request.getHeader("X-User-Id")?.let(UUID::fromString)
                ?: UUID.fromString("087a4d5a-9a7b-4ab1-b64f-7c7b00b3e104")
        }
    }
}
