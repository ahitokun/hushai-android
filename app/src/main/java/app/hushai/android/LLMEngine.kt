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

    suspend fun downloadModel(tierId: String) {
        val model = MODELS[tierId] ?: return
        val file = File(modelsDir, model.fileName)

        if (file.exists() && file.length() > 1000000) {
            _downloadState.value = DownloadState.Complete
            return
        }

        withContext(Dispatchers.IO) {
            try {
                _downloadState.value = DownloadState.Downloading(0f, 0, model.sizeMB)
                var connection = URL(model.url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true
                // Follow redirects manually (HuggingFace uses 302s)
                if (connection.responseCode in 301..303 || connection.responseCode == 307) {
                    val redirect = connection.getHeaderField("Location")
                    connection.disconnect()
                    connection = URL(redirect).openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 30000
                    connection.readTimeout = 30000
                }
                val totalBytes = connection.contentLengthLong
                val input = connection.getInputStream()
                val output = file.outputStream()
                val buffer = ByteArray(8192)
                var downloaded = 0L

                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    val progress = if (totalBytes > 0) downloaded.toFloat() / totalBytes else 0f
                    _downloadState.value = DownloadState.Downloading(
                        progress, (downloaded / 1048576).toInt(), model.sizeMB
                    )
                }
                output.close()
                input.close()
                _downloadState.value = DownloadState.Complete
            } catch (e: Exception) {
                file.delete()
                _downloadState.value = DownloadState.Error(e.message ?: "Download failed")
            }
        }
    }

    fun cancelDownload(tierId: String) {
        val model = MODELS[tierId] ?: return
        val file = File(modelsDir, model.fileName)
        file.delete()
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
