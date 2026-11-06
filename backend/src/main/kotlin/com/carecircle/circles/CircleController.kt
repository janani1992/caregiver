package com.carecircle.circles

import com.carecircle.auth.CurrentUserProvider
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/v1/circles")
class CircleController(
    private val circleService: CircleService,
    private val currentUserProvider: CurrentUserProvider,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createCircle(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: CreateCircleRequest,
    ): CircleResponse {
        if (idempotencyKey.isBlank() || idempotencyKey.length > 128) {
            throw InvalidCircleRequestException("Idempotency-Key must be 1-128 characters")
        }
        return circleService.create(request, currentUserProvider.currentUserId(), idempotencyKey)
    }
}

class InvalidCircleRequestException(message: String) : RuntimeException(message)
class CircleConflictException(message: String) : RuntimeException(message)
