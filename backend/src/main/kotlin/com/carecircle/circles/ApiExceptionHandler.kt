package com.carecircle.circles

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(InvalidCircleRequestException::class, MethodArgumentNotValidException::class)
    fun badRequest(exception: Exception) = ResponseEntity
        .status(HttpStatus.BAD_REQUEST)
        .body(ApiError(exception.message ?: "Invalid request"))

    @ExceptionHandler(CircleConflictException::class)
    fun conflict(exception: CircleConflictException) = ResponseEntity
        .status(HttpStatus.CONFLICT)
        .body(ApiError(exception.message ?: "Conflict"))
}

data class ApiError(val message: String)
