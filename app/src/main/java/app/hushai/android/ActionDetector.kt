package app.hushai.android

import android.net.Uri

data class DetectedAction(
    val type: ActionType,
    val label: String,
    val uri: String
)

enum class ActionType { PHONE, EMAIL, LOCATION, WHATSAPP }

object ActionDetector {
    private val PHONE_PATTERN = Regex("""(?<!\d)(\+?\d{1,3}[-.\s]?)?(\(?\d{2,4}\)?[-.\s]?)?\d{3,4}[-.\s]?\d{3,4}(?!\d)""")
    private val EMAIL_PATTERN = Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""")
    private val GEO_PATTERN = Regex("""geo:[^\s]+""")
    private val TEL_PATTERN = Regex("""tel:[^\s]+""")
    private val MAILTO_PATTERN = Regex("""mailto:[^\s]+""")
    private val ADDRESS_PATTERN = Regex("""(\d+\s+[A-Z][a-zA-Z]+(\s+[A-Z][a-zA-Z]+)*\s+(St|Ave|Blvd|Rd|Dr|Way|Ln|Street|Avenue|Boulevard|Road|Drive|Highway)\.?)""")

    fun detect(text: String, installedApps: Set<String> = emptySet()): List<DetectedAction> {
        val actions = mutableListOf<DetectedAction>()
        val lower = text.lowercase()

        // Already-formatted URIs
        GEO_PATTERN.findAll(text).forEach { actions.add(DetectedAction(ActionType.LOCATION, "📍 Open in Maps", it.value)) }
        TEL_PATTERN.findAll(text).forEach { actions.add(DetectedAction(ActionType.PHONE, "📞 ${it.value.removePrefix("tel:")}", it.value)) }
        MAILTO_PATTERN.findAll(text).forEach { actions.add(DetectedAction(ActionType.EMAIL, "✉️ ${it.value.removePrefix("mailto:")}", it.value)) }

        // Phone numbers in natural text
        if (actions.none { it.type == ActionType.PHONE }) {
            PHONE_PATTERN.findAll(text).forEach { match ->
                val num = match.value.replace(Regex("[^+\\d]"), "")
                if (num.length >= 7 || num in listOf("911", "100", "112", "999")) {
                    if (actions.none { it.type == ActionType.PHONE && it.uri.replace(Regex("[^\\d]"), "") == num.replace(Regex("[^\\d]"), "") }) {
                        actions.add(DetectedAction(ActionType.PHONE, "📞 Call $num", "tel:$num"))
                    }
                }
            }
        }

        // Emails
        if (actions.none { it.type == ActionType.EMAIL }) {
            EMAIL_PATTERN.findAll(text).forEach { actions.add(DetectedAction(ActionType.EMAIL, "✉️ Email ${it.value}", "mailto:${it.value}")) }
        }

        // Addresses → Maps search
        if (actions.none { it.type == ActionType.LOCATION }) {
            ADDRESS_PATTERN.findAll(text).take(2).forEach { match ->
                actions.add(DetectedAction(ActionType.LOCATION, "📍 \"${match.value}\" in Maps", "geo:0,0?q=${Uri.encode(match.value)}"))
            }
        }

        // WhatsApp if installed + messaging context
        if ("com.whatsapp" in installedApps && actions.any { it.type == ActionType.PHONE } && listOf("message", "text", "whatsapp", "send").any { it in lower }) {
            val phone = actions.first { it.type == ActionType.PHONE }
            val num = phone.uri.removePrefix("tel:").replace("+", "")
            actions.add(DetectedAction(ActionType.WHATSAPP, "💬 WhatsApp $num", "https://wa.me/$num"))
        }

        return actions.distinctBy { it.uri }.take(3)
    }
}
