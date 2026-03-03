package app.hushai.android

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import app.hushai.android.ui.theme.OfflineAITheme
import androidx.compose.runtime.collectAsState
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.android.play.core.review.ReviewManagerFactory

data class Message(val content: String, val isUser: Boolean, val isStreaming: Boolean = false)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Crash logging — saves to prefs, user can send via Settings
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val prefs = getSharedPreferences("offlineai", MODE_PRIVATE)
                val log = prefs.getString("crash_log", "") ?: ""
                val entry = "[${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] ${throwable.message}\n${throwable.stackTraceToString().take(500)}\n---\n"
                prefs.edit().putString("crash_log", (entry + log).take(5000)).apply()
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
        setContent {
            OfflineAITheme {
                val context = LocalContext.current
                val prefs = context.getSharedPreferences("offlineai", Context.MODE_PRIVATE)
                var screen by remember { mutableStateOf(
                    when {
                        !prefs.getBoolean("onboarded", false) -> "onboarding"
                        !prefs.getBoolean("model_selected", false) -> "model"
                        else -> "chat"
                    }
                ) }
                val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                actManager.getMemoryInfo(memInfo)
                val deviceRam = (memInfo.totalMem / (1024 * 1024 * 1024)).toInt()

                val llmEngine = remember { LLMEngine(context) }
                var selectedTier by remember { mutableStateOf(prefs.getString("model_tier", "smart") ?: "smart") }
                val downloadState by llmEngine.downloadState.collectAsState()
                val coroutineScope = rememberCoroutineScope()

                when (screen) {
                    "onboarding" -> OnboardingScreen(onGetStarted = {
                        prefs.edit().putBoolean("onboarded", true).apply()
                        screen = "model"
                    })
                    "model" -> ModelSelectionScreen(deviceRam = deviceRam, onModelSelected = { tier ->
                        selectedTier = tier
                        prefs.edit().putString("model_tier", tier).apply()
                        if (llmEngine.isModelDownloaded(tier)) {
                            prefs.edit().putBoolean("model_selected", true).apply()
                            screen = "chat"
                        } else {
                            screen = "download"
                        }
                    })
                    "download" -> DownloadScreen(
                        tierId = selectedTier,
                        downloadState = downloadState,
                        onStartDownload = { coroutineScope.launch { llmEngine.downloadModel(selectedTier) } },
                        onComplete = {
                            prefs.edit().putBoolean("model_selected", true).apply()
                            screen = "chat"
                        },
                        onCancel = {
                            llmEngine.cancelDownload(selectedTier)
                            screen = "model"
                        }
                    )
                    else -> ChatScreen(llmEngine = llmEngine, modelTier = selectedTier, deviceRam = deviceRam, onSwitchModel = { tier -> selectedTier = tier; prefs.edit().putString("model_tier", tier).apply(); if (!llmEngine.isModelDownloaded(tier)) { screen = "download" } })
                }
            }
        }
    }
}


fun detectInstalledApps(context: Context): String {
    val apps = mapOf(
        "com.google.android.apps.maps" to "Google Maps",
        "com.whatsapp" to "WhatsApp",
        "org.telegram.messenger" to "Telegram",
        "com.google.android.gm" to "Gmail",
        "com.google.android.apps.translate" to "Google Translate",
        "com.google.android.calendar" to "Google Calendar",
        "org.thoughtcrime.securesms" to "Signal"
    )
    val installed = apps.filter { (pkg, _) ->
        try { context.packageManager.getPackageInfo(pkg, 0); true }
        catch (_: Exception) { false }
    }.values
    return if (installed.isEmpty()) "" else "Installed apps: ${installed.joinToString(", ")}."
}

fun parseMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val linkPattern = Regex("""(geo:[^\s]+|tel:[^\s]+|mailto:[^\s]+|https://wa\.me/[^\s]+)""")
        while (i < text.length) {
            // Check for links first
            val linkMatch = linkPattern.find(text, i)
            if (linkMatch != null && linkMatch.range.first == i) {
                val url = linkMatch.value
                pushStringAnnotation("URL", url)
                withStyle(SpanStyle(color = Color(0xFF6C63FF), fontWeight = FontWeight.Medium)) {
                    val display = when {
                        url.startsWith("geo:") -> "📍 Open in Maps"
                        url.startsWith("tel:") -> "📞 ${url.removePrefix("tel:")}"
                        url.startsWith("mailto:") -> "✉️ ${url.removePrefix("mailto:")}"
                        url.startsWith("https://wa.me/") -> "💬 WhatsApp"
                        else -> url
                    }
                    append(display)
                }
                pop()
                i = linkMatch.range.last + 1
                continue
            }
            when {
                i + 1 < text.length && text[i] == '*' && text[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                i == 0 && text[i] == '-' && i + 1 < text.length && text[i + 1] == ' ' -> {
                    append("  •  "); i += 2
                }
                i > 0 && text[i - 1] == '\n' && text[i] == '-' && i + 1 < text.length && text[i + 1] == ' ' -> {
                    append("  •  "); i += 2
                }
                else -> { append(text[i]); i++ }
            }
        }
    }
}


@Composable
fun ActionButtons(actions: List<DetectedAction>) {
    if (actions.isEmpty()) return
    val context = LocalContext.current
    Column(modifier = Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        actions.forEach { action ->
            Surface(
                onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.uri))) }
                    catch (_: Exception) { Toast.makeText(context, "No app found", Toast.LENGTH_SHORT).show() }
                },
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF6C63FF).copy(alpha = 0.1f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(action.label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = Color(0xFF6C63FF), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ChatScreen(llmEngine: LLMEngine, modelTier: String = "smart", deviceRam: Int = 8, onSwitchModel: (String) -> Unit = {}) {
    val chatContext = LocalContext.current
    val installedApps = remember { detectInstalledApps(chatContext) }
    var messages by remember { mutableStateOf(listOf<Message>()) }
    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var currentEngine by remember { mutableStateOf<InferenceEngineV2?>(null) }
    val convoDB = remember { ConversationDB(chatContext) }
    var currentConvoId by remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var savedConvos by remember { mutableStateOf(convoDB.loadAll()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var showModelMenu by remember { mutableStateOf(false) }
    var pdfText by remember { mutableStateOf<String?>(null) }
    var pdfName by remember { mutableStateOf("") }
    val MAX_FILE_SIZE = 10 * 1024 * 1024L // 10MB limit
    val isBudget = deviceRam <= 5
    val docCharBudget = if (isBudget) 5000 else 10000
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            var name = "document"
            try {
                val cursor = chatContext.contentResolver.query(uri, null, null, null, null)
                cursor?.use { if (it.moveToFirst()) { val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (idx >= 0) name = it.getString(idx) } }
            } catch (_: Exception) {}
            pdfName = name.substringAfterLast("/").substringAfterLast(":")
            val extracted = PdfReader.extractFromUri(chatContext, uri)
            if (extracted == "[OCR_NEEDED]") {
                messages = messages + Message("🔍 Scanning with OCR...", isUser = false)
                scope.launch {
                    val ocrText = PdfReader.extractWithOCR(chatContext, it, maxChars = docCharBudget)
                    if (ocrText.startsWith("⚠️") || ocrText.startsWith("Error")) {
                        pdfText = null
                        messages = messages.dropLast(1) + Message(ocrText, isUser = false)
                    } else {
                        pdfText = ocrText.take(docCharBudget)
                        val totalPages = try { PdfReader.getPageCount(chatContext, it) } catch (_: Exception) { 0 }
                        val charRatio = minOf(1.0, docCharBudget.toDouble() / maxOf(1, ocrText.length).toDouble())
                        val pagesRead = maxOf(1, (totalPages * charRatio).toInt())
                        val pageMsg = if (pagesRead < totalPages) "$pagesRead of $totalPages pages analyzed (device limit)" else "$totalPages pages analyzed"
                        messages = messages.dropLast(1) + Message("📄 $pdfName — $pageMsg. Ask me anything.", isUser = false)
                    }
                }
            } else {
                pdfText = extracted.take(docCharBudget)
                val totalPages = try { PdfReader.getPageCount(chatContext, it) } catch (_: Exception) { 0 }
                if (totalPages > 0) {
                    messages = messages + Message("📄 $pdfName loaded ($totalPages pages). Ask me anything.", isUser = false)
                } else {
                    messages = messages + Message("📄 $pdfName loaded. Ask me anything.", isUser = false)
                }
            }

        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp), drawerContainerColor = Color(0xFF1A1A2E)) {
                Spacer(Modifier.height(16.dp).statusBarsPadding())
                Button(
                    onClick = { if (!isGenerating) { messages = emptyList(); pdfText = null; pdfName = ""; currentConvoId = java.util.UUID.randomUUID().toString(); scope.launch { drawerState.close() } } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C63FF))
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New conversation")
                }
                // Saved conversations
                Spacer(Modifier.height(16.dp))
                if (savedConvos.isNotEmpty()) {
                    Text("Recent", modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp), fontSize = 12.sp, color = Color.White.copy(alpha = 0.4f))
                    savedConvos.take(10).forEach { convo ->
                        Surface(
                            onClick = {
                                if (!isGenerating) {
                                    currentConvoId = convo.id
                                    messages = convo.messages
                                    pdfText = null; pdfName = ""
                                    scope.launch { drawerState.close() }
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (convo.id == currentConvoId) Color.White.copy(alpha = 0.1f) else Color.Transparent,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp)
                        ) {
                            Row(modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(convo.title, modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                                    fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                                IconButton(onClick = {
                                    convoDB.delete(convo.id)
                                    savedConvos = convoDB.loadAll()
                                    if (currentConvoId == convo.id) { messages = emptyList(); pdfText = null; pdfName = ""; currentConvoId = java.util.UUID.randomUUID().toString() }
                                }, modifier = Modifier.size(28.dp)) {
                                    Text("✕", fontSize = 12.sp, color = Color.White.copy(alpha = 0.3f))
                                }
                            }
                        }
                    }
                }
                if (savedConvos.isNotEmpty()) {
                    TextButton(onClick = {
                        savedConvos.forEach { convoDB.delete(it.id) }
                        savedConvos = emptyList()
                        messages = emptyList()
                        currentConvoId = java.util.UUID.randomUUID().toString()
                        scope.launch { drawerState.close() }
                    }, modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text("Clear all", fontSize = 12.sp, color = Color.Red.copy(alpha = 0.4f))
                    }
                }
                Spacer(Modifier.weight(1f))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                TextButton(onClick = { uriHandler.openUri("mailto:hello@hushai.app?subject=Hush AI Feedback") },
                    modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text("Send feedback", fontSize = 12.sp, color = Color.White.copy(alpha = 0.4f))
                }
                TextButton(onClick = {
                    val crashLog = chatContext.getSharedPreferences("offlineai", 0).getString("crash_log", "") ?: ""
                    if (crashLog.isBlank()) { Toast.makeText(chatContext, "No crash logs", Toast.LENGTH_SHORT).show() }
                    else { uriHandler.openUri("mailto:hello@hushai.app?subject=Hush AI Crash Log&body=${java.net.URLEncoder.encode(crashLog, "UTF-8")}") }
                }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text("Send crash log", fontSize = 12.sp, color = Color.White.copy(alpha = 0.3f))
                }
                TextButton(onClick = { uriHandler.openUri("https://hushai.app/download") },
                    modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text("🖥️ Also on Windows — hushai.app", fontSize = 12.sp, color = Color.White.copy(alpha = 0.3f))
                }
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🔒", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("100% Private", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("No data leaves this device", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Lock, "Private", tint = Color(0xFF6C63FF), modifier = Modifier.size(20.dp).drawBehind { drawCircle(color = Color(0xFF6C63FF).copy(alpha = 0.3f), radius = 24f) })
                            Spacer(Modifier.width(6.dp))
                            Text("Hush AI", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Spacer(Modifier.width(8.dp))
                            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF34A853)) {
                                Text("  Private  ", fontSize = 10.sp, color = Color.White,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, "Menu")
                        }
                    },
                    actions = {
                        Box {
                            TextButton(onClick = { showModelMenu = true }) {
                                val emoji = when (modelTier) { "swift" -> "⚡"; "genius" -> "🧠"; else -> "🎯" }
                                Text("$emoji ${modelTier.replaceFirstChar { it.uppercase() }}", fontSize = 13.sp)
                            }
                            DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
                                MODEL_TIERS.forEach { tier ->
                                    val id = tier.id
                                    val label = "${tier.emoji} ${tier.name}"
                                    val canRun = deviceRam >= tier.minRamGB
                                    val downloaded = llmEngine.isModelDownloaded(id)
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(label, color = if (canRun) Color.Unspecified else Color.Gray.copy(alpha = 0.4f))
                                                if (modelTier == id) { Spacer(Modifier.width(8.dp)); Text("●", color = Color(0xFF34A853), fontSize = 8.sp) }
                                                if (!canRun) { Spacer(Modifier.width(8.dp)); Text("${tier.minRamGB}GB+ RAM", fontSize = 10.sp, color = Color.Red.copy(alpha = 0.5f)) }
                                                else if (!downloaded) { Spacer(Modifier.width(8.dp)); Text("↓ Download", fontSize = 11.sp, color = Color.Gray) }
                                            }
                                        },
                                        enabled = canRun,
                                        onClick = {
                                            showModelMenu = false
                                            onSwitchModel(id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
                ) {
                    if (messages.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 100.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Outlined.Lock, "Private", tint = Color(0xFF6C63FF), modifier = Modifier.size(48.dp))
                                Spacer(Modifier.height(16.dp))
                                Text("How can I help?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Text("Fully private. Runs on your device.", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
                                Spacer(Modifier.height(32.dp))
                                listOf("Summarize a document" to "\uD83D\uDCC4", "Help me write an email" to "\u2709\uFE0F", "Ask me anything" to "\uD83D\uDD12").forEach { (label, icon) ->
                                    Surface(onClick = { inputText = label }, shape = RoundedCornerShape(16.dp), color = Color(0xFF6C63FF).copy(alpha = 0.08f), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6C63FF).copy(alpha = 0.15f)), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(icon, fontSize = 18.sp)
                                            Spacer(Modifier.width(12.dp))
                                            Text(label, fontSize = 14.sp, color = Color(0xFF6C63FF).copy(alpha = 0.8f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    items(messages) { msg ->
                        Column(modifier = Modifier.fillMaxWidth().animateContentSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
                            Text(
                                text = if (msg.isUser) "You" else "Hush AI",
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = if (msg.isUser) MaterialTheme.colorScheme.primary else Color(0xFF6C63FF),
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                            if (!msg.isUser && msg.isStreaming && (msg.content == "Loading AI..." || msg.content == "Thinking...")) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFF6C63FF)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text("Generating response...", fontSize = 13.sp, color = Color.Gray)
                                        if (messages.count { !it.isUser && !it.isStreaming && it.content.isNotBlank() } == 0) {
                                            Text("First response may take longer while the model warms up", fontSize = 10.sp, color = Color.Gray.copy(alpha = 0.5f))
                                        }
                                    }
                                }
                            } else {
                                val annotated = parseMarkdown(msg.content + if (msg.isStreaming) " ●" else "")
                                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                                androidx.compose.foundation.text.ClickableText(
                                    text = annotated,
                                    style = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.onSurface),
                                    onClick = { offset ->
                                        annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                                            try { uriHandler.openUri(it.item) } catch (_: Exception) {}
                                        }
                                    }
                                )
                            }
                            if (!msg.isUser && !msg.isStreaming && msg.content.isNotBlank()) {
                                // Action buttons (deep links)
                                val prevUser = messages.lastOrNull { m -> m.isUser && messages.indexOf(m) < messages.indexOf(msg) }?.content.orEmpty()
                                val combined = prevUser + " " + msg.content
                                val detectedActions = remember(combined) { ActionDetector.detect(combined) }
                                ActionButtons(detectedActions)
                                var copied by remember { mutableStateOf(false) }
                                TextButton(onClick = {
                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(msg.content))
                                    copied = true
                                }, modifier = Modifier.padding(top = 2.dp)) {
                                    Text(if (copied) "✓ Copied" else "📋 Copy", fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.3f), modifier = Modifier.padding(horizontal = 20.dp))
                    }
                }

                Text("Responses are AI-generated and may not always be accurate.", fontSize = 11.sp, color = Color.Gray.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.5f))
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (modelTier != "swift") { IconButton(onClick = { filePicker.launch(arrayOf("application/pdf", "text/plain", "text/html", "text/csv", "text/markdown", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword")) }) { Icon(Icons.Default.Add, "Attach", tint = Color.Gray) } }
                    OutlinedTextField(
                        value = inputText, onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message", color = Color.Gray) },
                        shape = RoundedCornerShape(24.dp), maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.LightGray, focusedBorderColor = MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.width(4.dp))
                    FilledIconButton(
                        onClick = {
                            if (isGenerating) {
                                currentEngine?.stop()
                                isGenerating = false
                            } else if (inputText.isNotBlank()) {
                                val userMsg = inputText.trim(); inputText = ""
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                messages = messages + Message(userMsg, isUser = true)
                                isGenerating = true
                                scope.launch {
                                    listState.animateScrollToItem(maxOf(0, messages.size - 1))
                                    messages = messages + Message("Loading AI...", isUser = false, isStreaming = true)
                                    val modelPath = llmEngine.getModelPath(modelTier)
                                    if (modelPath != null) {
                                        val engine = currentEngine ?: InferenceEngineV2(chatContext, deviceRam)
                                        currentEngine = engine
                                        var fullText = ""
                                        engine.loadModel(modelPath,
                                            onLoaded = {
                                                scope.launch(Dispatchers.Main) { messages = messages.dropLast(1) + Message("Thinking...", isUser = false, isStreaming = true) }
                                                val history = messages.filter { !it.isStreaming && it.content != "Loading AI..." && it.content != "Thinking..." }.dropLast(1).map { m ->
                                                    if (m.isUser) "user" to m.content else "assistant" to m.content
                                                }
                                                val promptWithContext = if (pdfText != null) {
                                                    "Context from document '$pdfName':\n${pdfText!!}\n\n---\nUser question: $userMsg"
                                                } else { userMsg }
                                                engine.generate(promptWithContext, history = history, tier = modelTier, installedApps = installedApps,
                                                    onToken = { token -> fullText += token; scope.launch(Dispatchers.Main) { messages = messages.dropLast(1) + Message(fullText, isUser = false, isStreaming = true); listState.animateScrollToItem(maxOf(0, messages.size - 1)) } },
                                                    onDone = {
                                                        scope.launch(Dispatchers.Main) {
                                                            messages = messages.dropLast(1) + Message(fullText, isUser = false)
                                                            isGenerating = false
                                                            // Auto-save conversation
                                                            val title = messages.firstOrNull { it.isUser }?.content?.take(40) ?: "New chat"
                                                            convoDB.save(Conversation(currentConvoId, title, messages.filter { !it.isStreaming }, modelTier, System.currentTimeMillis()))
                                                            savedConvos = convoDB.loadAll()
                                                            // Review prompt after 4th successful response
                                                            val responseCount = chatContext.getSharedPreferences("offlineai", 0).getInt("response_count", 0) + 1
                                                            chatContext.getSharedPreferences("offlineai", 0).edit().putInt("response_count", responseCount).apply()
                                                            if (responseCount == 4) {
                                                                try {
                                                                    val reviewManager = ReviewManagerFactory.create(chatContext)
                                                                    reviewManager.requestReviewFlow().addOnSuccessListener { reviewInfo ->
                                                                        reviewManager.launchReviewFlow(chatContext as android.app.Activity, reviewInfo)
                                                                    }
                                                                } catch (_: Exception) {}
                                                            }
                                                        }
                                                        // engine stays loaded for next message
                                                    },
                                                    onError = { err -> scope.launch(Dispatchers.Main) { messages = messages.dropLast(1) + Message("Error: $err", isUser = false); isGenerating = false } }
                                                )
                                            },
                                            onError = { err -> scope.launch(Dispatchers.Main) { messages = messages.dropLast(1) + Message("⚠️ Couldn't load the AI model. This usually means your device needs more free RAM.\n\nTry:\n- Close other apps and try again\n- Switch to a smaller model (Smart or Swift)\n- Restart your phone if the issue persists", isUser = false); isGenerating = false } }
                                        )
                                    } else {
                                        messages = messages.dropLast(1) + Message("Model not found. Re-download needed.", isUser = false)
                                        isGenerating = false
                                    }
                                }
                            }
                        },
                        enabled = isGenerating || inputText.isNotBlank(),
                        shape = CircleShape, modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isGenerating) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    ) { Icon(if (isGenerating) Icons.Default.Close else Icons.Default.Send, if (isGenerating) "Stop" else "Send", modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }
}
