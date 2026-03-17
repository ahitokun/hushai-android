package app.hushai.android.actions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object PermissionGate {

    fun hasContacts(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun hasCalendarRead(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Which permission does this action need? Null = no permission needed. */
    fun requiredPermission(type: ActionType): String? = when (type) {
        ActionType.MESSAGE, ActionType.CALL -> Manifest.permission.READ_CONTACTS
        ActionType.CALENDAR -> null  // Uses intent, no permission needed to create events
        ActionType.EMAIL -> null
    }

    fun hasPermission(context: Context, type: ActionType): Boolean {
        val perm = requiredPermission(type) ?: return true
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }
}
