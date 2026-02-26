/*
 * hush_llama_bridge.cpp — JNI bridge for Hush AI
 *
 * Minimal JNI wrapper around llama.cpp for Android ARM64.
 * Tested against llama.cpp b7446. Supports Qwen3 GGUF models.
 *
 * Key design decisions:
 * - Depends only on llama.h (not common.h) to avoid build breakage
 *   across llama.cpp versions. The common/ dir changes constantly.
 * - Uses the sampler chain API (llama_sampler_chain_*) instead of
 *   the removed llama_sampling_* functions.
 * - All token ops go through llama_model_get_vocab() — the old
 *   model-based tokenize/detokenize functions are deprecated.
 * - rope_freq_base = 0 so it reads from GGUF metadata. Qwen3
 *   stores its own RoPE config. Never hardcode this.
 * - LLAMA_BUILD_COMMON=OFF in CMake. We don't need it and it
 *   breaks between releases (arg.h -> common.h rename, etc).
 *
 * See CMakeLists.txt for the full build setup.
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <atomic>
#include <mutex>

// Only need the core public header — NOT common.h, NOT sampling.h
#include "llama.h"

#define TAG "HushLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// ============================================================================
// Global state — one model loaded at a time (fine for a phone app)
// ============================================================================

static struct {
    llama_model   *model   = nullptr;
    llama_context *ctx     = nullptr;
    llama_sampler *sampler = nullptr;

    const llama_vocab *vocab = nullptr;  // non-owning pointer from model

    std::atomic<bool> is_generating{false};
    std::atomic<bool> stop_requested{false};

    std::mutex mtx;  // protects load/release
} g_state;

// ============================================================================
// Helper: tokenize a string
// ============================================================================
static std::vector<llama_token> tokenize(const std::string &text,
                                         bool add_special,
                                         bool parse_special) {
    // First call with nullptr to get the count
    int n = llama_tokenize(g_state.vocab,
                           text.c_str(),
                           (int)text.length(),
                           nullptr, 0,
                           add_special,
                           parse_special);
    // n is negative when buffer is too small — its absolute value is the count needed
    std::vector<llama_token> tokens(std::abs(n));

    int actual = llama_tokenize(g_state.vocab,
                                text.c_str(),
                                (int)text.length(),
                                tokens.data(),
                                (int)tokens.size(),
                                add_special,
                                parse_special);
    if (actual < 0) {
        LOGE("tokenize failed: needed %d tokens", -actual);
        tokens.clear();
    } else {
        tokens.resize(actual);
    }
    return tokens;
}

// ============================================================================
// Helper: detokenize a single token to string
// ============================================================================
static std::string token_to_piece(llama_token token) {
    char buf[256];
    // Args: vocab, token, buf, buf_size, special_tokens_rendering, render_special
    int n = llama_token_to_piece(g_state.vocab, token, buf, sizeof(buf),
                                 0,     // special = 0 (don't render special tokens as text)
                                 true); // render_special = true
    if (n < 0) {
        // Buffer too small — shouldn't happen with 256 bytes but handle it
        std::vector<char> big(std::abs(n) + 1);
        n = llama_token_to_piece(g_state.vocab, token, big.data(), (int)big.size(), 0, true);
        if (n > 0) return std::string(big.data(), n);
        return "";
    }
    return std::string(buf, n);
}

// ============================================================================
// JNI: Load model
// ============================================================================

extern "C" JNIEXPORT jboolean JNICALL
Java_app_hushai_android_NativeBridge_loadModel(
    JNIEnv *env,
    jobject /* this */,
    jstring jModelPath,
    jint    nCtx,       // context size (e.g., 2048 for phone)
    jint    nThreads    // number of CPU threads (e.g., 4)
) {
    std::lock_guard<std::mutex> lock(g_state.mtx);

    // Release any previously loaded model
    if (g_state.sampler) { llama_sampler_free(g_state.sampler); g_state.sampler = nullptr; }
    if (g_state.ctx)     { llama_free(g_state.ctx);             g_state.ctx = nullptr; }
    if (g_state.model)   { llama_model_free(g_state.model);     g_state.model = nullptr; }
    g_state.vocab = nullptr;

    // Initialize backend (safe to call multiple times — it's idempotent)
    llama_backend_init();

    const char *model_path = env->GetStringUTFChars(jModelPath, nullptr);
    LOGI("Loading model: %s", model_path);

    // ---- Model params ----
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;      // CPU only on Android (no Metal/CUDA)
    model_params.use_mmap = true;       // Memory-map the GGUF — critical for phones
    model_params.use_mlock = false;     // Don't lock in RAM (let Android manage memory)

    // Load the model
    g_state.model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jModelPath, model_path);

    if (!g_state.model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    // Get vocabulary (non-owning pointer — valid as long as model is alive)
    g_state.vocab = llama_model_get_vocab(g_state.model);
    if (!g_state.vocab) {
        LOGE("Failed to get vocab from model");
        llama_model_free(g_state.model);
        g_state.model = nullptr;
        return JNI_FALSE;
    }

    // ---- Context params ----
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx       = (uint32_t)nCtx;         // context window size
    ctx_params.n_batch     = 512;                     // max tokens per decode call
    ctx_params.n_ubatch    = 512;                     // physical batch size
    ctx_params.n_threads   = (int32_t)nThreads;       // generation threads
    ctx_params.n_threads_batch = (int32_t)nThreads;   // prompt processing threads
    ctx_params.no_perf     = true;                    // disable perf timing (less overhead)

    // CRITICAL: set to 0 to read from GGUF metadata
    // Qwen3 stores its own RoPE config — don't hardcode this
    ctx_params.rope_freq_base  = 0.0f;   // 0 = from model
    ctx_params.rope_freq_scale = 0.0f;   // 0 = from model

    // Flash attention: let llama.cpp decide based on hardware

    g_state.ctx = llama_new_context_with_model(g_state.model, ctx_params);
    if (!g_state.ctx) {
        LOGE("Failed to create context (OOM? Try smaller n_ctx or smaller model)");
        llama_model_free(g_state.model);
        g_state.model = nullptr;
        g_state.vocab = nullptr;
        return JNI_FALSE;
    }

    // ---- Sampler chain ----
    // The modern llama.cpp sampling API uses a chain of samplers.
    // Each sampler modifies the token probability distribution, then the
    // final sampler (dist or greedy) picks the actual token.
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;

    g_state.sampler = llama_sampler_chain_init(sparams);

    // Repetition penalty — critical for small models to avoid loops
    llama_sampler_chain_add(g_state.sampler,
        llama_sampler_init_penalties(
            64,     // penalty_last_n: look back 64 tokens
            1.1f,   // penalty_repeat
            0.0f,   // penalty_freq
            0.0f    // penalty_present
        ));

    // Top-K filtering
    llama_sampler_chain_add(g_state.sampler,
        llama_sampler_init_top_k(40));

    // Min-P filtering (better than top-p for small models)
    llama_sampler_chain_add(g_state.sampler,
        llama_sampler_init_min_p(0.05f, 1));

    // Temperature
    llama_sampler_chain_add(g_state.sampler,
        llama_sampler_init_temp(0.7f));

    // Final distribution sampler (picks the token)
    // Use dist with random seed for varied outputs
    llama_sampler_chain_add(g_state.sampler,
        llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    LOGI("Model loaded successfully. Context: %d tokens, Threads: %d",
         nCtx, nThreads);

    return JNI_TRUE;
}

// ============================================================================
// JNI: Generate (streaming via callback)
// ============================================================================


// Safe JNI string creation — handles non-standard UTF-8 from llama.cpp
static jstring safeNewStringUTF(JNIEnv *env, const std::string &str) {
    // NewStringUTF can crash on invalid modified UTF-8
    // Use byte array + String constructor instead for safety
    jbyteArray bytes = env->NewByteArray((jsize)str.size());
    if (!bytes) return env->NewStringUTF("?");
    env->SetByteArrayRegion(bytes, 0, (jsize)str.size(), (const jbyte*)str.data());
    
    jclass strClass = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(strClass, "<init>", "([BLjava/lang/String;)V");
    jstring encoding = env->NewStringUTF("UTF-8");
    jstring result = (jstring)env->NewObject(strClass, ctor, bytes, encoding);
    
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(encoding);
    return result ? result : env->NewStringUTF("?");
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_hushai_android_NativeBridge_generate(
    JNIEnv *env,
    jobject thiz,
    jstring jPrompt,
    jint    maxTokens
) {
    if (!g_state.model || !g_state.ctx || !g_state.sampler) {
        return env->NewStringUTF("[Error: Model not loaded]");
    }

    if (g_state.is_generating.exchange(true)) {
        return env->NewStringUTF("[Error: Generation already in progress]");
    }

    g_state.stop_requested.store(false);
    llama_memory_clear(llama_get_memory(g_state.ctx), true);
    llama_sampler_reset(g_state.sampler);

    const char *prompt_cstr = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(jPrompt, prompt_cstr);

    // Tokenize the prompt
    // add_special=true adds BOS if the model expects it
    // parse_special=true handles <|im_start|> etc. in the prompt text
    std::vector<llama_token> tokens = tokenize(prompt, true, true);

    if (tokens.empty()) {
        LOGE("Tokenization produced no tokens");
        g_state.is_generating.store(false);
        return env->NewStringUTF("[Error: Failed to tokenize prompt]");
    }

    LOGD("Prompt tokenized: %zu tokens", tokens.size());

    // Clear the KV cache for a fresh generation
    llama_memory_clear(llama_get_memory(g_state.ctx), true);

    // ---- Process the prompt in chunks (prefill) ----
    int n_batch = 512;
    int n_tokens = (int)tokens.size();
    for (int i = 0; i < n_tokens; i += n_batch) {
        if (g_state.stop_requested.load()) break;
        int chunk = std::min(n_batch, n_tokens - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, chunk);
        if (llama_decode(g_state.ctx, batch) != 0) {
            LOGE("llama_decode failed at chunk %d/%d", i, n_tokens);
            g_state.is_generating.store(false);
            return env->NewStringUTF("[Error: Failed to process prompt]");
        }
    }

    // ---- Token generation loop ----
    std::string result;
    int n_decoded = (int)tokens.size();

    // Get the JNI callback method for streaming
    jclass cls = env->GetObjectClass(thiz);
    jmethodID onToken = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");

    for (int i = 0; i < maxTokens; i++) {
        if (g_state.stop_requested.load()) {
            LOGD("Generation stopped by user after %d tokens", i);
            break;
        }

        // Sample the next token using the sampler chain
        llama_token new_token = llama_sampler_sample(g_state.sampler, g_state.ctx, -1);

        // Accept the token (updates internal sampler state, e.g. repetition tracking)
        llama_sampler_accept(g_state.sampler, new_token);

        // Check for end-of-generation
        if (llama_token_is_eog(g_state.vocab, new_token)) {
            LOGD("Hit EOG token after %d generated tokens", i);
            break;
        }

        // Convert token to text
        std::string piece = token_to_piece(new_token);

        // Skip empty pieces
        if (piece.empty()) continue;

        result += piece;

        // Stream the token back to Kotlin via JNI callback
        if (onToken) {
            jstring jPiece = safeNewStringUTF(env, piece);
            env->CallVoidMethod(thiz, onToken, jPiece);
            env->DeleteLocalRef(jPiece);
        }

        // Prepare batch for the next token
        llama_batch single = llama_batch_get_one(&new_token, 1);
        if (llama_decode(g_state.ctx, single) != 0) {
            LOGE("llama_decode failed at token %d", i);
            break;
        }

        n_decoded++;
    }

    g_state.is_generating.store(false);
    LOGD("Generation complete: %zu chars", result.size());

    return safeNewStringUTF(env, result);
}

// ============================================================================
// JNI: Stop generation
// ============================================================================

extern "C" JNIEXPORT void JNICALL
Java_app_hushai_android_NativeBridge_stopGeneration(
    JNIEnv *env,
    jobject /* this */
) {
    LOGD("Stop requested");
    g_state.stop_requested.store(true);
}

// ============================================================================
// JNI: Release model and free all resources
// ============================================================================

extern "C" JNIEXPORT void JNICALL
Java_app_hushai_android_NativeBridge_release(
    JNIEnv *env,
    jobject /* this */
) {
    std::lock_guard<std::mutex> lock(g_state.mtx);

    LOGI("Releasing model resources");

    if (g_state.sampler) { llama_sampler_free(g_state.sampler); g_state.sampler = nullptr; }
    if (g_state.ctx)     { llama_free(g_state.ctx);             g_state.ctx = nullptr; }
    if (g_state.model)   { llama_model_free(g_state.model);     g_state.model = nullptr; }
    g_state.vocab = nullptr;

    llama_backend_free();
}

// ============================================================================
// JNI: Check if model is loaded
// ============================================================================

extern "C" JNIEXPORT jboolean JNICALL
Java_app_hushai_android_NativeBridge_isModelLoaded(
    JNIEnv *env,
    jobject /* this */
) {
    return (g_state.model != nullptr && g_state.ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

// ============================================================================
// JNI: Get model info (for debugging / UI display)
// ============================================================================

extern "C" JNIEXPORT jstring JNICALL
Java_app_hushai_android_NativeBridge_getModelInfo(
    JNIEnv *env,
    jobject /* this */
) {
    if (!g_state.model || !g_state.vocab) {
        return env->NewStringUTF("No model loaded");
    }

    char desc[256];
    llama_model_desc(g_state.model, desc, sizeof(desc));

    int n_vocab = llama_vocab_n_tokens(g_state.vocab);
    int n_ctx = (int)llama_n_ctx(g_state.ctx);

    std::string info = std::string("Model: ") + desc +
                       "\nVocab: " + std::to_string(n_vocab) +
                       "\nContext: " + std::to_string(n_ctx);

    return env->NewStringUTF(info.c_str());
}