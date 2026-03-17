package app.hushai.android.actions

import android.content.Context
import android.util.Log
import app.hushai.android.NativeBridge
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/**
 * IntentRouter — uses FunctionGemma to classify user intent.
 * Falls back gracefully: if router not loaded, returns null (no action).
 */
object IntentRouter {

    private const val TAG = "IntentRouter"
    private const val ROUTER_FILENAME = "hush-functiongemma.Q8_0.gguf"
    private var bridge: NativeBridge? = null
    private var ready = false

    /** Initialize router. Call once at app startup on a background thread. */
    fun init(context: Context, nativeBridge: NativeBridge) {
        bridge = nativeBridge
        val modelFile = File(context.filesDir, "models/$ROUTER_FILENAME")
        if (!modelFile.exists()) {
            Log.w(TAG, "Router model not found at ${modelFile.absolutePath}")
            return
        }
        try {
            ready = nativeBridge.loadRouter(modelFile.absolutePath)
            Log.i(TAG, "Router loaded: $ready")
        } catch (e: Exception) {
            Log.e(TAG, "Router load failed: ${e.message}")
            ready = false
        }
    }

    /** Check if router is ready */
    fun isReady(): Boolean = ready && (bridge?.isRouterLoaded() == true)

    // FunctionGemma prompt template — must match training format
    private fun buildPrompt(userMessage: String): String {
        return """<start_of_turn>user
You have access to these tools:
- send_message(contact, body, app): Send a text message via WhatsApp, SMS, Signal, or Telegram
- make_call(contact): Make a phone call
- send_email(to, subject, body): Send an email
- create_calendar_event(title, date, time): Create a calendar event or reminder
- open_app(name): Open an application, website, or folder
- no_action(): User is just chatting, no action needed

User: $userMessage
<end_of_turn>
<start_of_turn>model
"""
    }

    /**
     * Classify user message. Returns HushAction or null (= no action / just chat).
     * Runs in ~100-300ms on phone CPU.
     */
    fun classify(userMessage: String): HushAction? {
        if (!isReady()) return null

        try {
            val prompt = buildPrompt(userMessage)
            val raw = bridge?.classifyIntent(prompt) ?: return null
            if (raw.isBlank()) return null
            return parseOutput(raw.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Classification failed: ${e.message}")
            return null
        }
    }

    /**
     * Parse FunctionGemma output.
     * Format: "call:function_name{json}" or "no_action"
     */
    private fun parseOutput(raw: String): HushAction? {
        // no_action
        if (raw.startsWith("no_action")) return null

        // Extract function name and JSON params
        if (!raw.startsWith("call:")) return null

        try {
            val afterCall = raw.removePrefix("call:")
            val braceIdx = afterCall.indexOf('{')
            if (braceIdx < 0) return null

            val funcName = afterCall.substring(0, braceIdx)
            // Find the FIRST complete JSON object (ignore trailing garbage)
            val jsonStart = afterCall.indexOf('{')
            val jsonEnd = afterCall.indexOf('}', jsonStart)
            if (jsonEnd < 0) return null

            val jsonStr = afterCall.substring(jsonStart, jsonEnd + 1)
            val params = JSONObject(jsonStr)

            return when (funcName) {
                "send_message" -> HushAction(
                    type = ActionType.MESSAGE,
                    contact = params.optString("contact", ""),
                    body = params.optString("body", ""),
                    app = params.optString("app", "whatsapp")
                )
                "make_call" -> HushAction(
                    type = ActionType.CALL,
                    contact = params.optString("contact", "")
                )
                "send_email" -> HushAction(
                    type = ActionType.EMAIL,
                    to = params.optString("to", ""),
                    subject = params.optString("subject", ""),
                    body = params.optString("body", "")
                )
                "create_calendar_event" -> HushAction(
                    type = ActionType.CALENDAR,
                    title = params.optString("title", ""),
                    date = params.optString("date", ""),
                    time = params.optString("time", "")
                )
                "open_app" -> HushAction(
                    type = ActionType.MESSAGE, // reuse for now
                    contact = params.optString("name", "")
                )
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse failed for: $raw — ${e.message}")
            return null
        }
    }

    /** Release router resources */
    fun release() {
        try { bridge?.releaseRouter() } catch (_: Exception) {}
        ready = false
    }
}
