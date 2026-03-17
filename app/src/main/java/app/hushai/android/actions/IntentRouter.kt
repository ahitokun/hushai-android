package app.hushai.android.actions

import android.content.Context
import android.util.Log
import app.hushai.android.NativeBridge
import java.io.File

/**
 * IntentRouter — uses FunctionGemma to classify user intent.
 * Falls back gracefully: if router not loaded, returns null (no action).
 */
object IntentRouter {

    private const val TAG = "IntentRouter"
    private const val ROUTER_FILENAME = "functiongemma-270m-it-Q8_0.gguf"
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

    // FunctionGemma prompt — developer role activates function calling logic
    private fun buildPrompt(userMessage: String): String {
        return "<start_of_turn>developer\n" +
            "You are a model that can do function calling with the following functions\n" +
            "<end_of_turn>\n" +
            "<start_of_turn>user\n" +
            "$userMessage\n" +
            "<end_of_turn>\n" +
            "<start_of_turn>model\n"
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
            Log.d(TAG, "Raw output: $raw")
            if (raw.isBlank()) return null
            return parseOutput(raw.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Classification failed: ${e.message}")
            return null
        }
    }

    /**
     * Parse FunctionGemma output.
     * Format: "call:function_name{key:<escape>value<escape>,key2:<escape>value2<escape>}"
     */
    private fun parseOutput(raw: String): HushAction? {
        if (raw.contains("no_action")) return null
        if (!raw.startsWith("call:")) return null

        try {
            val afterCall = raw.removePrefix("call:")
            val braceIdx = afterCall.indexOf('{')
            if (braceIdx < 0) return null

            val funcName = afterCall.substring(0, braceIdx)
            val inner = afterCall.substring(braceIdx + 1).substringBefore('}')

            // Parse key:<escape>value<escape> pairs
            val params = mutableMapOf<String, String>()
            for (pair in inner.split(",")) {
                val kv = pair.split(":", limit = 2)
                if (kv.size == 2) {
                    params[kv[0].trim()] = kv[1].trim()
                        .removePrefix("<escape>").removeSuffix("<escape>").trim()
                }
            }

            return when (funcName) {
                "send_message" -> HushAction(
                    type = ActionType.MESSAGE,
                    contact = params["contact"] ?: "",
                    body = params["body"] ?: "",
                    app = params["app"] ?: "whatsapp"
                )
                "make_call" -> HushAction(
                    type = ActionType.CALL,
                    contact = params["contact"] ?: ""
                )
                "send_email" -> HushAction(
                    type = ActionType.EMAIL,
                    to = params["to"] ?: "",
                    subject = params["subject"] ?: "",
                    body = params["body"] ?: ""
                )
                "create_calendar_event" -> HushAction(
                    type = ActionType.CALENDAR,
                    title = params["title"] ?: "",
                    date = params["date"] ?: "",
                    time = params["time"] ?: ""
                )
                "open_app" -> HushAction(
                    type = ActionType.MESSAGE,
                    contact = params["name"] ?: ""
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
