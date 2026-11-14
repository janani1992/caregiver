package com.carecircle.circles

import com.carecircle.carelog.CareLogConflictException
import com.carecircle.carelog.CareLogEncryptionUnavailableException
import com.carecircle.carelog.CareLogForbiddenException
import com.carecircle.carelog.CareLogInvalidRequestException
import com.carecircle.invites.InviteConflictException
import com.carecircle.invites.InviteExpiredException
import com.carecircle.invites.InviteForbiddenException
import com.carecircle.invites.InviteInvalidRequestException
import com.carecircle.invites.InviteNotFoundException
import com.carecircle.members.MemberConflictException
import com.carecircle.members.MemberForbiddenException
import com.carecircle.members.MemberInvalidRequestException
import com.carecircle.members.MemberNotFoundException
import com.carecircle.push.PushSubscriptionConflictException
import com.carecircle.push.PushSubscriptionForbiddenException
import com.carecircle.push.PushSubscriptionInvalidRequestException
import com.carecircle.push.PushSubscriptionNotFoundException
import com.carecircle.shopping.ListItemConflictException
import com.carecircle.shopping.ListItemForbiddenException
import com.carecircle.shopping.ListItemInvalidRequestException
import com.carecircle.shopping.ListItemNotFoundException
import com.carecircle.urgent.UrgentRequestConflictException
import com.carecircle.urgent.UrgentRequestForbiddenException
import com.carecircle.urgent.UrgentRequestInvalidRequestException
import com.carecircle.urgent.UrgentRequestNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(CareLogInvalidRequestException::class)
    fun careLogBadRequest(exception: CareLogInvalidRequestException) = ResponseEntity
        .status(HttpStatus.BAD_REQUEST)
        .body(ApiError(exception.message ?: "Invalid care log request"))

    @ExceptionHandler(CareLogForbiddenException::class)
    fun careLogForbidden(exception: CareLogForbiddenException) = ResponseEntity
        .status(HttpStatus.FORBIDDEN)
        .body(ApiError(exception.message ?: "Forbidden"))

    @ExceptionHandler(CareLogConflictException::class)
    fun careLogConflict(exception: CareLogConflictException) = ResponseEntity
        .status(HttpStatus.CONFLICT)
        .body(ApiError(exception.message ?: "Care log conflict"))

    @ExceptionHandler(CareLogEncryptionUnavailableException::class)
    fun careLogEncryptionUnavailable(exception: CareLogEncryptionUnavailableException) = ResponseEntity
        .status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(ApiError(exception.message ?: "Care log is temporarily unavailable"))

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

    @ExceptionHandler(UrgentRequestForbiddenException::class)
    fun urgentRequestForbidden(exception: UrgentRequestForbiddenException) = ResponseEntity
        .status(HttpStatus.FORBIDDEN)
        .body(ApiError(exception.message ?: "Forbidden"))

    @ExceptionHandler(UrgentRequestNotFoundException::class)
    fun urgentRequestNotFound(exception: UrgentRequestNotFoundException) = ResponseEntity
        .status(HttpStatus.NOT_FOUND)
        .body(ApiError(exception.message ?: "Urgent request not found"))

    @ExceptionHandler(UrgentRequestConflictException::class)
    fun urgentRequestConflict(exception: UrgentRequestConflictException) = ResponseEntity
        .status(HttpStatus.CONFLICT)
        .body(ApiError(exception.message ?: "Urgent request conflict"))

    @ExceptionHandler(UrgentRequestInvalidRequestException::class)
    fun urgentRequestBadRequest(exception: UrgentRequestInvalidRequestException) = ResponseEntity
        .status(HttpStatus.BAD_REQUEST)
        .body(ApiError(exception.message ?: "Invalid request"))

    @ExceptionHandler(PushSubscriptionForbiddenException::class)
    fun pushSubscriptionForbidden(exception: PushSubscriptionForbiddenException) = ResponseEntity
        .status(HttpStatus.FORBIDDEN)
        .body(ApiError(exception.message ?: "Forbidden"))

    @ExceptionHandler(PushSubscriptionNotFoundException::class)
    fun pushSubscriptionNotFound(exception: PushSubscriptionNotFoundException) = ResponseEntity
        .status(HttpStatus.NOT_FOUND)
        .body(ApiError(exception.message ?: "Push subscription not found"))

    @ExceptionHandler(PushSubscriptionConflictException::class)
    fun pushSubscriptionConflict(exception: PushSubscriptionConflictException) = ResponseEntity
        .status(HttpStatus.CONFLICT)
        .body(ApiError(exception.message ?: "Push subscription conflict"))

    @ExceptionHandler(PushSubscriptionInvalidRequestException::class)
    fun pushSubscriptionBadRequest(exception: PushSubscriptionInvalidRequestException) = ResponseEntity
        .status(HttpStatus.BAD_REQUEST)
        .body(ApiError(exception.message ?: "Invalid push subscription"))
}

data class ApiError(val message: String)
