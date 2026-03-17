package app.hushai.android.actions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ActionCard(
    action: HushAction,
    contactName: String?,
    contactPhone: String?,
    onConfirm: () -> Unit,
    onEdit: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val (icon, label) = when (action.type) {
        ActionType.MESSAGE -> "💬" to "Send Message"
        ActionType.CALL -> "📞" to "Make Call"
        ActionType.EMAIL -> "✉️" to "Send Email"
        ActionType.CALENDAR -> "📅" to "Add to Calendar"
    }

    var editing by remember { mutableStateOf(false) }
    var editedBody by remember { mutableStateOf(action.body) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1A1A2E),
        border = BorderStroke(1.dp, Color(0xFF6C63FF).copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White)
            }

            Spacer(Modifier.height(12.dp))

            // Details
            when (action.type) {
                ActionType.MESSAGE -> {
                    DetailRow("To", contactName ?: action.contact)
                    if (!contactPhone.isNullOrBlank()) DetailRow("Phone", contactPhone)
                    DetailRow("Via", if (action.app == "sms") "SMS" else "WhatsApp")
                    if (editing) {
                        OutlinedTextField(
                            value = editedBody, onValueChange = { editedBody = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                focusedBorderColor = Color(0xFF6C63FF)
                            )
                        )
                    } else {
                        DetailRow("Message", action.body)
                    }
                }
                ActionType.CALL -> {
                    DetailRow("Contact", contactName ?: action.contact)
                    if (!contactPhone.isNullOrBlank()) DetailRow("Phone", contactPhone)
                }
                ActionType.EMAIL -> {
                    DetailRow("To", action.to)
                    DetailRow("Subject", action.subject)
                    if (editing) {
                        OutlinedTextField(
                            value = editedBody, onValueChange = { editedBody = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            minLines = 3,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                focusedBorderColor = Color(0xFF6C63FF)
                            )
                        )
                    } else {
                        DetailRow("Body", action.body.take(200) + if (action.body.length > 200) "..." else "")
                    }
                }
                ActionType.CALENDAR -> {
                    DetailRow("Event", action.title)
                    DetailRow("Date", action.date)
                    if (action.time.isNotBlank()) DetailRow("Time", action.time)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Privacy badge
            Text(
                "🔒 Processed on your device. Never sent anywhere.",
                fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f)
            )

            Spacer(Modifier.height(12.dp))

            // Buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (editing) onEdit?.invoke(editedBody)
                        onConfirm()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C63FF)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) { Text(if (action.type == ActionType.CALL) "Call" else "Send") }

                if (onEdit != null && action.type in listOf(ActionType.MESSAGE, ActionType.EMAIL)) {
                    OutlinedButton(
                        onClick = { editing = !editing },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                    ) { Text(if (editing) "Done" else "Edit", color = Color.White.copy(alpha = 0.7f)) }
                }

                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) { Text("Cancel", color = Color.White.copy(alpha = 0.5f)) }
            }
        }
    }
}

@Composable
fun PermissionRequestCard(
    actionType: ActionType,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    val (desc, what) = when (actionType) {
        ActionType.MESSAGE, ActionType.CALL -> "find contacts on your phone" to "Contacts"
        ActionType.CALENDAR -> "read and create calendar events" to "Calendar"
        ActionType.EMAIL -> return // no permission needed
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1A1A2E),
        border = BorderStroke(1.dp, Color(0xFF6C63FF).copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("🔒 $what Access Needed", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(
                "I can $desc to complete this action. Everything stays on your device — never sent anywhere.",
                fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f), lineHeight = 20.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAllow,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C63FF)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("Allow Access") }
                OutlinedButton(
                    onClick = onDeny,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) { Text("Not Now", color = Color.White.copy(alpha = 0.5f)) }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", fontSize = 13.sp, color = Color.White.copy(alpha = 0.5f))
        Text(value, fontSize = 13.sp, color = Color.White.copy(alpha = 0.9f))
    }
}
