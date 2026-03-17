package app.hushai.android

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.net.URL

data class ModelInfo(
    val id: String,
    val name: String,
    val url: String,
    val fileName: String,
    val sizeMB: Int
)

val MODELS = mapOf(
    "swift" to ModelInfo(
        "swift", "Swift",
        "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf",
        "qwen3.5-0.8b-q4.gguf", 533
    ),
    "smart" to ModelInfo(
        "smart", "Smart",
        "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf",
        "qwen3.5-2b-q4.gguf", 1500
    ),
    "genius" to ModelInfo(
        "genius", "Genius",
        "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf",
        "qwen3.5-4b-q4.gguf", 2740
    )
)

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float, val downloadedMB: Int, val totalMB: Int) : DownloadState()
    object Complete : DownloadState()
    data class Error(val message: String) : DownloadState()
}

sealed class LLMState {
    object NotLoaded : LLMState()
    object Loading : LLMState()
    object Ready : LLMState()
    data class Error(val message: String) : LLMState()
}

class LLMEngine(private val context: Context) {

    private var downloadJob: kotlinx.coroutines.Job? = null
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState

    private val _llmState = MutableStateFlow<LLMState>(LLMState.NotLoaded)
    val llmState: StateFlow<LLMState> = _llmState

    private val modelsDir = File(context.filesDir, "models")

    init { modelsDir.mkdirs() }

    companion object {
        const val ROUTER_URL = "https://huggingface.co/unsloth/functiongemma-270m-it-GGUF/resolve/main/functiongemma-270m-it-Q8_0.gguf"
        const val ROUTER_FILENAME = "functiongemma-270m-it-Q8_0.gguf"
        const val ROUTER_SIZE_MB = 292
    }

    fun isRouterDownloaded(): Boolean {
        val file = File(modelsDir, ROUTER_FILENAME)
        return file.exists() && file.length() > ROUTER_SIZE_MB * 900000L
    }

    fun isModelDownloaded(tierId: String): Boolean {
        val model = MODELS[tierId] ?: return false
        val file = File(modelsDir, model.fileName)
        return file.exists() && file.length() > 1000000
    }

    fun getModelPath(tierId: String): String? {
        val model = MODELS[tierId] ?: return null
        val file = File(modelsDir, model.fileName)
        return if (file.exists()) file.absolutePath else null
    }

    /** Single download function — downloads router + chat model as one seamless flow */
    suspend fun downloadModel(tierId: String) {
        val model = MODELS[tierId] ?: return
        val chatFile = File(modelsDir, model.fileName)
        val routerFile = File(modelsDir, ROUTER_FILENAME)
        val needsRouter = tierId != "swift" && !isRouterDownloaded()
        val needsChat = !chatFile.exists() || chatFile.length() < model.sizeMB * 1000000L * 0.95

        if (!needsRouter && !needsChat) {
            _downloadState.value = DownloadState.Complete
            return
        }

        // Total size = router (if needed) + chat model (if needed)
        val totalMB = (if (needsRouter) ROUTER_SIZE_MB else 0) + (if (needsChat) model.sizeMB else 0)

        withContext(Dispatchers.IO) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "hushai:download")
            wakeLock.acquire(60 * 60 * 1000L)
            downloadCancelled = false
            try {
                var totalDownloaded = 0L
                val totalBytes = totalMB * 1048576L

                _downloadState.value = DownloadState.Downloading(0f, 0, totalMB)

                // Phase 1: Download router (if needed, Smart/Genius only)
                if (needsRouter) {
                    try {
                        totalDownloaded = downloadFile(ROUTER_URL, routerFile, totalDownloaded, totalBytes, totalMB)
                    } catch (e: Exception) {
                        // Router download failed — continue with chat model anyway
                        android.util.Log.e("LLMEngine", "Router download failed: ${e.message}")
                    }
                }

                // Phase 2: Download chat model (if needed)
                if (needsChat) {
                    totalDownloaded = downloadFile(model.url, chatFile, totalDownloaded, totalBytes, totalMB)
                }

                _downloadState.value = DownloadState.Complete
            } catch (e: Exception) {
                _downloadState.value = DownloadState.Error(e.message ?: "Download failed")
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    @Volatile private var downloadCancelled = false

    /** Download a single file with resume support and progress reporting */
    private fun downloadFile(url: String, destFile: File, startOffset: Long, totalBytes: Long, totalMB: Int): Long {
        val tempFile = File(destFile.absolutePath + ".partial")
        var existingBytes = if (tempFile.exists()) tempFile.length() else 0L

        var connection = URL(url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 30000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true
        if (existingBytes > 0) connection.setRequestProperty("Range", "bytes=$existingBytes-")

        if (connection.responseCode in 301..303 || connection.responseCode == 307) {
            val redirect = connection.getHeaderField("Location")
            connection.disconnect()
            connection = URL(redirect).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            if (existingBytes > 0) connection.setRequestProperty("Range", "bytes=$existingBytes-")
        }

        val input = connection.inputStream
        val output = java.io.FileOutputStream(tempFile, existingBytes > 0)
        val buffer = ByteArray(8192)
        var fileDownloaded = existingBytes
        var totalDownloaded = startOffset + existingBytes

        while (true) {
            if (downloadCancelled) { output.close(); input.close(); return totalDownloaded }
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            fileDownloaded += read
            totalDownloaded += read
            val progress = if (totalBytes > 0) totalDownloaded.toFloat() / totalBytes else 0f
            _downloadState.value = DownloadState.Downloading(
                progress, (totalDownloaded / 1048576).toInt(), totalMB
            )
        }
        output.close()
        input.close()
        tempFile.renameTo(destFile)
        return totalDownloaded
    }

    fun cancelDownload(tierId: String) {
        downloadCancelled = true
        _downloadState.value = DownloadState.Idle
    }

    fun deleteModel(tierId: String) {
        val model = MODELS[tierId] ?: return
        File(modelsDir, model.fileName).delete()
    }

    fun getDownloadedModelSize(): Long {
        return modelsDir.listFiles()?.sumOf { it.length() } ?: 0
    }
}
