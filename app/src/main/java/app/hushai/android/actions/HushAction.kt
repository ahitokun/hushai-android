package app.hushai.android.actions

data class HushAction(
    val type: ActionType,
    val contact: String = "",
    val app: String = "",
    val body: String = "",
    val to: String = "",
    val subject: String = "",
    val title: String = "",
    val date: String = "",
    val time: String = ""
)

enum class ActionType { MESSAGE, CALL, EMAIL, CALENDAR }
