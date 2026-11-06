package com.carecircle.circles

import com.carecircle.invites.InviteConflictException
import com.carecircle.invites.InviteExpiredException
import com.carecircle.invites.InviteForbiddenException
import com.carecircle.invites.InviteInvalidRequestException
import com.carecircle.invites.InviteNotFoundException
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

    @ExceptionHandler(InviteInvalidRequestException::class)
    fun inviteBadRequest(exception: InviteInvalidRequestException) = ResponseEntity
        .status(HttpStatus.BAD_REQUEST)
        .body(ApiError(exception.message ?: "Invalid request"))

    @ExceptionHandler(CircleConflictException::class)
    fun conflict(exception: CircleConflictException) = ResponseEntity
        .status(HttpStatus.CONFLICT)
        .body(ApiError(exception.message ?: "Conflict"))

    @ExceptionHandler(InviteConflictException::class)
    fun inviteConflict(exception: InviteConflictException) = ResponseEntity
        .status(HttpStatus.CONFLICT)
        .body(ApiError(exception.message ?: "Invite conflict"))

    @ExceptionHandler(InviteForbiddenException::class)
    fun inviteForbidden(exception: InviteForbiddenException) = ResponseEntity
        .status(HttpStatus.FORBIDDEN)
        .body(ApiError(exception.message ?: "Forbidden"))

    @ExceptionHandler(InviteNotFoundException::class)
    fun inviteNotFound(exception: InviteNotFoundException) = ResponseEntity
        .status(HttpStatus.NOT_FOUND)
        .body(ApiError(exception.message ?: "Invite not found"))

    @ExceptionHandler(InviteExpiredException::class)
    fun inviteExpired(exception: InviteExpiredException) = ResponseEntity
        .status(HttpStatus.GONE)
        .body(ApiError(exception.message ?: "Invite expired"))
}

data class ApiError(val message: String)
