package com.carecircle.auth

import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** Never enable the local profile outside development: this header is not authentication. */
@Component
@Profile("local")
class LocalHeaderCurrentUserProvider : CurrentUserProvider {
    override fun currentUserId(): UUID {
        val request = (RequestContextHolder.currentRequestAttributes() as? ServletRequestAttributes)?.request
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required")
        val rawId = request.getHeader("X-User-Id")
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required")
        return runCatching { UUID.fromString(rawId) }
            .getOrElse { throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid development user ID") }
    }
}
