package com.carecircle.circles

import com.carecircle.invites.InviteConflictException
import com.carecircle.invites.InviteExpiredException
import com.carecircle.invites.InviteForbiddenException
import com.carecircle.invites.InviteInvalidRequestException
import com.carecircle.invites.InviteNotFoundException
import com.carecircle.members.MemberConflictException
import com.carecircle.members.MemberForbiddenException
import com.carecircle.members.MemberInvalidRequestException
import com.carecircle.members.MemberNotFoundException
import com.carecircle.shopping.ListItemConflictException
import com.carecircle.shopping.ListItemForbiddenException
import com.carecircle.shopping.ListItemInvalidRequestException
import com.carecircle.shopping.ListItemNotFoundException
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

    @ExceptionHandler(MemberForbiddenException::class)
    fun memberForbidden(exception: MemberForbiddenException) = ResponseEntity
        .status(HttpStatus.FORBIDDEN)
        .body(ApiError(exception.message ?: "Forbidden"))

    @ExceptionHandler(MemberNotFoundException::class)
    fun memberNotFound(exception: MemberNotFoundException) = ResponseEntity
        .status(HttpStatus.NOT_FOUND)
        .body(ApiError(exception.message ?: "Member not found"))

    @ExceptionHandler(MemberConflictException::class)
    fun memberConflict(exception: MemberConflictException) = ResponseEntity
        .status(HttpStatus.CONFLICT)
        .body(ApiError(exception.message ?: "Member conflict"))

    @ExceptionHandler(MemberInvalidRequestException::class)
    fun memberBadRequest(exception: MemberInvalidRequestException) = ResponseEntity
        .status(HttpStatus.BAD_REQUEST)
        .body(ApiError(exception.message ?: "Invalid request"))

    @ExceptionHandler(ListItemForbiddenException::class)
    fun listItemForbidden(exception: ListItemForbiddenException) = ResponseEntity
        .status(HttpStatus.FORBIDDEN)
        .body(ApiError(exception.message ?: "Forbidden"))

    @ExceptionHandler(ListItemNotFoundException::class)
    fun listItemNotFound(exception: ListItemNotFoundException) = ResponseEntity
        .status(HttpStatus.NOT_FOUND)
        .body(ApiError(exception.message ?: "List item not found"))

    @ExceptionHandler(ListItemConflictException::class)
    fun listItemConflict(exception: ListItemConflictException) = ResponseEntity
        .status(HttpStatus.CONFLICT)
        .body(ApiError(exception.message ?: "List item conflict"))

    @ExceptionHandler(ListItemInvalidRequestException::class)
    fun listItemBadRequest(exception: ListItemInvalidRequestException) = ResponseEntity
        .status(HttpStatus.BAD_REQUEST)
        .body(ApiError(exception.message ?: "Invalid request"))
}

data class ApiError(val message: String)
