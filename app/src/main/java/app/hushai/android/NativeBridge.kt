/**
 * NativeBridge.kt — JNI interface to hush_llama_bridge.cpp
 */

package app.hushai.android

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * JNI bridge to llama.cpp for on-device inference.
 *
 * Usage:
 *   val bridge = NativeBridge()
 *   bridge.loadModel("/path/to/qwen3-1.7b-q4_k_m.gguf", 2048, 4)
 *   bridge.generate(formattedPrompt, 500) { token -> updateUI(token) }
 *   bridge.release()
 */
class NativeBridge {

    companion object {
        private const val TAG = "NativeBridge"

        init {
            try {
                // Load both the llama shared lib and our bridge
                // Order matters: llama must be loaded first
                System.loadLibrary("llama")
                System.loadLibrary("hush_llama_bridge")
                Log.i(TAG, "Native libraries loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native libraries: ${e.message}")
            }
        }
    }

    // --- JNI declarations (must match hush_llama_bridge.cpp exactly) ---

    /**
     * Load a GGUF model from disk.
     *
     * @param modelPath Absolute path to the .gguf file on device storage
     * @param nCtx      Context window size in tokens (2048 recommended for phone)
     * @param nThreads  Number of CPU threads (4-6 recommended)
     * @return true if model loaded successfully
     */
    external fun loadModel(modelPath: String, nCtx: Int, nThreads: Int): Boolean

    /**
     * Generate text from a formatted prompt.
     *
     * The prompt should already include the chat template formatting
     * (e.g., <|im_start|>system\n...<|im_end|>\n<|im_start|>user\n...).
     *
     * This method is BLOCKING — call from a coroutine on Dispatchers.IO.
     *
     * During generation, [onToken] is called for each generated token piece.
     * This happens on the native thread, so use Dispatchers.Main to update UI.
     *
     * @param prompt    Fully formatted prompt string with chat template tokens
     * @param maxTokens Maximum tokens to generate (500 is a good default)
     * @return The complete generated text
     */
    external fun generate(prompt: String, maxTokens: Int): String

    /** Request generation to stop. Safe to call from any thread. */
    external fun stopGeneration()

    /** Release all native resources. Call when switching models or destroying activity. */
    external fun release()

    /** Check if a model is currently loaded and ready. */
    external fun isModelLoaded(): Boolean

    /** Get model description string (for debug/UI). */
    external fun getModelInfo(): String

    // --- Callback from native code (called during generate()) ---

    /**
     * Called by JNI for each generated token.
     * Override or set a listener to handle streaming.
     *
     * NOTE: This runs on the native inference thread, NOT the main thread.
     */
    var tokenCallback: ((String) -> Unit)? = null

    @Suppress("unused") // Called from JNI
    fun onToken(token: String) {
        tokenCallback?.invoke(token)
    }
}


/**
 * InferenceEngineV2 — wraps NativeBridge for the chat UI layer.
 * Handles model loading, prompt formatting, streaming, and cleanup.
 */
class InferenceEngineV2(private val appContext: android.content.Context, private val deviceRam: Int = 8) {

    private val isBudget = deviceRam <= 5

    private val bridge = NativeBridge()
    private var isLoaded = false
    private var loadedPath: String? = null

    /**
     * Load the model. Call once, not per message.
     *
     * @param path     Absolute file path to the GGUF model
     * @param onLoaded Called on success
     * @param onError  Called on failure with error message
     */
    fun loadModel(path: String, onLoaded: () -> Unit, onError: (String) -> Unit) {
        if (path == loadedPath && isLoaded) {
            Log.d("InferenceV2", "Model already loaded, skipping")
            onLoaded()
            return
        }
        val nThreads = maxOf(2, minOf(4, Runtime.getRuntime().availableProcessors() / 2))
        Log.d("InferenceV2", "Loading model: $path (threads=$nThreads)")

        CoroutineScope(Dispatchers.IO.limitedParallelism(1)).launch {
            try {
                val nCtx = if (path.contains("0.6b") || isBudget) 2048 else 4096
                val success = bridge.loadModel(path, nCtx, nThreads)
                withContext(Dispatchers.Main) {
                    if (success) {
                        isLoaded = true
                        loadedPath = path
                        Log.i("InferenceV2", "Model loaded: ${bridge.getModelInfo()}")
                        onLoaded()
                    } else {
                        isLoaded = false
                        onError("Failed to load model. Device may not have enough free RAM.")
                    }
                }
            } catch (e: Exception) {
                Log.e("InferenceV2", "Load failed", e)
                withContext(Dispatchers.Main) { onError(e.message ?: "Load failed") }
            }
        }
    }

    /**
     * Generate a response (streaming).
     *
     * @param prompt  The user's message (will be wrapped in chat template)
     * @param history Conversation history as (role, content) pairs
     * @param tier    Model tier for response length guidance
     * @param onToken Called for each generated token piece
     * @param onDone  Called when generation completes
     * @param onError Called on error
     */
    fun generate(
        prompt: String,
        history: List<Pair<String, String>> = emptyList(),
        tier: String = "smart",
        installedApps: String = "",
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isLoaded) {
            onError("Model not loaded")
            return
        }

        // Build the formatted prompt with chat template
        val formatted = buildChatPrompt(prompt, history, tier, installedApps)

        var tokenCount = 0
        val startTime = System.currentTimeMillis()
        var insideThink = false
        var outputStarted = false

        // Set up streaming callback
        bridge.tokenCallback = fun(token: String) {
            // Track think blocks
            if (token.contains("<think>")) insideThink = true
            if (token.contains("</think>")) { insideThink = false; return }
            if (insideThink) return

            // Filter out special tokens
            val clean = token
                .replace("<|im_end|>", "")
                .replace("<|im_start|>", "")
                .replace("<|endoftext|>", "")
                .replace("<|eot_id|>", "")
                .replace("</s>", "")
                .replace("<think>", "")
                .replace("</think>", "")

            // Skip leading whitespace before first real content
            if (!outputStarted && clean.isBlank()) return
            if (clean.isNotEmpty()) {
                outputStarted = true
                tokenCount++
                onToken(clean)
            }
        }

        // Run generation on IO thread
        CoroutineScope(Dispatchers.IO.limitedParallelism(1)).launch {
            try {
                val result = bridge.generate(formatted, 500)
                withContext(Dispatchers.Main) {
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                    val timeStr = if (elapsed >= 60) "${(elapsed / 60).toInt()}m ${(elapsed % 60).toInt()}s" else String.format("%.1f", elapsed) + "s"
                    onToken("\n\n⚡ ${timeStr}")
                    onDone()
                }
            } catch (e: Exception) {
                Log.e("InferenceV2", "Generation failed", e)
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Generation failed")
                }
            }
        }
    }

    /**
     * Build the ChatML-formatted prompt that Qwen3 expects.
     *
     * Format:
     *   <|im_start|>system\n{system_prompt}<|im_end|>
     *   <|im_start|>user\n{user_msg}<|im_end|>
     *   <|im_start|>assistant\n
     */
    private fun buildChatPrompt(
        userMessage: String,
        history: List<Pair<String, String>>,
        tier: String,
        installedApps: String = ""
    ): String {
        val lengthGuide = when (tier) {
            "swift" -> "Be brief. 2-3 sentences max."
            "genius" -> "Give thorough answers. Use examples when helpful. Up to 2-3 short paragraphs."
            else -> "Give clear, focused answers. 1-2 short paragraphs max."
        }

        val systemPrompt = if (isBudget) {
            buildString {
                append("You are Hush AI, a private offline assistant. ")
                append("Be honest. Never invent phone numbers or URLs. Respond in the user's language. ")
                append("/no_think\n")
                append(lengthGuide)
                
                if (installedApps.isNotEmpty()) append(" $installedApps")
            }
        } else {
            val now = java.text.SimpleDateFormat(
                "EEEE, MMMM d, yyyy", java.util.Locale.getDefault()
            ).format(java.util.Date())

            buildString {
                append("You are Hush AI, a private assistant running on-device. ")
                append("No data leaves this phone. Today is $now. ")
                append("Respond in the user's language.\n")
                append("Be helpful and share what you know. Never invent specific phone numbers, URLs, or addresses. If asked about current hours, prices, or whether a place is still open, note that your info may be outdated. ")
                append("You work offline with no internet access.\n")
                append("/no_think\n")
                append("STYLE: $lengthGuide Match the user's tone.\n")
                append("When mentioning places, include the full name and address if known. When mentioning phone numbers, include the full number.\n")
                if (installedApps.isNotEmpty()) append("$installedApps\n")
            }
        }

        val maxTurns = if (isBudget) 5 else 6

        return buildString {
            append("<|im_start|>system\n$systemPrompt<|im_end|>\n")

            val recentHistory = history.takeLast(maxTurns)
            for ((role, content) in recentHistory) {
                append("<|im_start|>$role\n$content<|im_end|>\n")
            }

            append("<|im_start|>user\n$userMessage<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }

    fun stop() {
        bridge.stopGeneration()
    }

    fun release() {
        stop()
        bridge.release()
        isLoaded = false
    }

    fun isReady(): Boolean = isLoaded && bridge.isModelLoaded()
}
