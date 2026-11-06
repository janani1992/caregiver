package com.carecircle.circles

import org.h2.api.Trigger
import java.sql.Connection
import java.sql.SQLException

class FailingMembershipInsertTrigger : Trigger {
    override fun fire(conn: Connection, oldRow: Array<out Any>?, newRow: Array<out Any>?) {
        throw SQLException("simulated owner membership insert failure")
    }
}
