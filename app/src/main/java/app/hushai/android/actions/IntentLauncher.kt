package app.hushai.android.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import java.util.Calendar
import java.util.TimeZone

object IntentLauncher {

    fun sendWhatsApp(context: Context, phone: String, message: String) {
        val clean = phone.replace(Regex("[^\\d+]"), "")
        val uri = "https://wa.me/$clean?text=${Uri.encode(message)}"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
    }

    fun sendSms(context: Context, phone: String, message: String) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).apply {
            putExtra("sms_body", message)
        }
        context.startActivity(intent)
    }

    fun dial(context: Context, phone: String) {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
    }

    fun sendEmail(context: Context, to: String, subject: String, body: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        context.startActivity(intent)
    }

    /** Open calendar app with pre-filled event. User taps Save. No WRITE_CALENDAR needed. */
    fun addCalendarEvent(context: Context, title: String, date: String, time: String) {
        val cal = Calendar.getInstance()
        try {
            if (date.isNotBlank() && date.contains("-")) {
                val parts = date.split("-").map { it.toInt() }
                cal.set(Calendar.YEAR, parts[0])
                cal.set(Calendar.MONTH, parts[1] - 1)
                cal.set(Calendar.DAY_OF_MONTH, parts[2])
            }
            if (time.isNotBlank() && time.contains(":")) {
                val timeParts = time.split(":").map { it.toInt() }
                cal.set(Calendar.HOUR_OF_DAY, timeParts[0])
                cal.set(Calendar.MINUTE, timeParts.getOrElse(1) { 0 })
            }
        } catch (_: Exception) { /* use current time as fallback */ }

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, cal.timeInMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, cal.timeInMillis + 3600000)
            putExtra(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        context.startActivity(intent)
    }
}
