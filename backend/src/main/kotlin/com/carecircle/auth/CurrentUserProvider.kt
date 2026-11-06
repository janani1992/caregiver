package com.carecircle.auth

import java.util.UUID

/** Supplies the authenticated user ID. The local profile uses a development-only header adapter. */
fun interface CurrentUserProvider {
    fun currentUserId(): UUID
}
