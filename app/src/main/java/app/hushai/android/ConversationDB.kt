package app.hushai.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Conversation(
    val id: String,
    val title: String,
    val messages: List<Message>,
    val modelTier: String,
    val timestamp: Long
)

class ConversationDB(context: Context) {
    private val dir = File(context.filesDir, "conversations").also { it.mkdirs() }

    fun save(convo: Conversation) {
        val json = JSONObject().apply {
            put("id", convo.id)
            put("title", convo.title)
            put("modelTier", convo.modelTier)
            put("timestamp", convo.timestamp)
            put("messages", JSONArray().apply {
                convo.messages.forEach { msg ->
                    put(JSONObject().apply {
                        put("content", msg.content)
                        put("isUser", msg.isUser)
                    })
                }
            })
        }
        File(dir, "${convo.id}.json").writeText(json.toString())
    }

    fun loadAll(): List<Conversation> {
        return dir.listFiles()?.filter { it.extension == "json" }?.mapNotNull { file ->
            try {
                val json = JSONObject(file.readText())
                val msgs = json.getJSONArray("messages")
                val messages = (0 until msgs.length()).map { i ->
                    val m = msgs.getJSONObject(i)
                    Message(m.getString("content"), m.getBoolean("isUser"))
                }
                Conversation(
                    id = json.getString("id"),
                    title = json.getString("title"),
                    messages = messages,
                    modelTier = json.optString("modelTier", "smart"),
                    timestamp = json.getLong("timestamp")
                )
            } catch (e: Exception) { null }
        }?.sortedByDescending { it.timestamp } ?: emptyList()
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}
