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
import app.hushai.android.actions.*

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

                // Handle Share-to-Hush intent
                val sharedText = remember {
                    if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
                        intent.getStringExtra(Intent.EXTRA_TEXT)
                    } else null
                }

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

                // Initialize FunctionGemma router in background (Smart/Genius only)
                LaunchedEffect(Unit) {
                    if (deviceRam >= 6 && llmEngine.isRouterDownloaded()) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            IntentRouter.init(context, app.hushai.android.NativeBridge())
                        }
                    }
                }
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
                            if (deviceRam >= 6 && llmEngine.isRouterDownloaded()) {
                                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    IntentRouter.init(context, app.hushai.android.NativeBridge())
                                }
                            }
                            screen = "chat"
                        },
                        onCancel = {
                            llmEngine.cancelDownload(selectedTier)
                            screen = "model"
                        }
                    )
                    else -> ChatScreen(llmEngine = llmEngine, modelTier = selectedTier, deviceRam = deviceRam, sharedText = sharedText, onSwitchModel = { tier -> selectedTier = tier; prefs.edit().putString("model_tier", tier).apply(); if (!llmEngine.isModelDownloaded(tier)) { screen = "download" } })
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
fun ChatScreen(llmEngine: LLMEngine, modelTier: String = "smart", deviceRam: Int = 8, sharedText: String? = null, onSwitchModel: (String) -> Unit = {}) {
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
    var attachedUri by remember { mutableStateOf<Uri?>(null) }
    var attachedName by remember { mutableStateOf("") }
    var attachedText by remember { mutableStateOf<String?>(null) }
    var attachedTruncated by remember { mutableStateOf(false) }
    var attachedLoading by remember { mutableStateOf(false) }

    // Action system state
    var pendingAction by remember { mutableStateOf<HushAction?>(null) }
    var resolvedContact by remember { mutableStateOf<ResolvedContact?>(null) }
    var needsPermission by remember { mutableStateOf<ActionType?>(null) }
    var editedBody by remember { mutableStateOf("") }
    var pendingUserAction by remember { mutableStateOf<HushAction?>(null) } // detected from user input, waiting for model response

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingAction != null) {
            val action = pendingAction!!
            needsPermission = null
            if (action.type == ActionType.MESSAGE || action.type == ActionType.CALL) {
                resolvedContact = try { ContactResolver.resolve(chatContext, action.contact) } catch (_: Exception) { null }
                if (resolvedContact == null) {
                    messages = messages + Message("I couldn't find \"${action.contact}\" in your contacts. What's their name in your phone?", isUser = false)
                    pendingAction = null
                }
            }
        } else {
            needsPermission = null
            pendingAction = null
            messages = messages + Message("No worries. You can enable this later in Settings → Apps → Hush AI → Permissions.", isUser = false)
        }
    }

    // Handle shared text from other apps
    var sharedContent by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) {
            sharedContent = sharedText
            inputText = "Explain this"
        }
    }

    fun clearAttachment() {
        attachedUri = null; attachedName = ""; attachedText = null
        attachedTruncated = false; attachedLoading = false
    }
    val docCharBudget = when (modelTier) {
        "swift" -> 2800    // 0.8B: nCtx=2048
        "smart" -> 10000   // 2B: nCtx=4096
        "genius" -> 24000  // 4B: nCtx=8192
        else -> 10000
    }
    val MAX_FILE_SIZE = 10 * 1024 * 1024L

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            clearAttachment()
            attachedUri = uri

            var name = "document"
            try {
                val cursor = chatContext.contentResolver.query(uri, null, null, null, null)
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0) name = c.getString(nameIdx)
                        val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (sizeIdx >= 0) {
                            val fileSize = c.getLong(sizeIdx)
                            if (fileSize > MAX_FILE_SIZE) {
                                messages = messages + Message("⚠️ ${name} is too large (${fileSize / 1048576}MB). Max is ${MAX_FILE_SIZE / 1048576}MB.", isUser = false)
                                clearAttachment()
                                return@let
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            attachedName = name.substringAfterLast("/").substringAfterLast(":")
            attachedLoading = true

            scope.launch {
                var extracted = PdfReader.extractFromUri(chatContext, uri)
                if (extracted == "[OCR_NEEDED]") {
                    extracted = PdfReader.extractWithOCR(chatContext, it, maxChars = docCharBudget)
                }
                if (extracted.startsWith("⚠️") || extracted.startsWith("Error")) {
                    messages = messages + Message(extracted, isUser = false)
                    clearAttachment()
                } else {
                    attachedTruncated = extracted.length > docCharBudget
                    attachedText = extracted.take(docCharBudget)
                }
                attachedLoading = false
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp), drawerContainerColor = Color(0xFF1A1A2E)) {
                Spacer(Modifier.height(16.dp).statusBarsPadding())
                Button(
                    onClick = { if (!isGenerating) { messages = emptyList(); clearAttachment(); pendingAction = null; resolvedContact = null; needsPermission = null; currentConvoId = java.util.UUID.randomUUID().toString(); scope.launch { drawerState.close() } } },
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
                                    clearAttachment()
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
                                    if (currentConvoId == convo.id) { messages = emptyList(); clearAttachment(); pendingAction = null; resolvedContact = null; needsPermission = null; currentConvoId = java.util.UUID.randomUUID().toString() }
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
                                                if (modelTier == id) { Spacer(Modifier.width(8.dp)); Text("● Active", color = Color(0xFF34A853), fontSize = 10.sp) }
                                                else if (!canRun) { Spacer(Modifier.width(8.dp)); Text("${tier.minRamGB}GB+ RAM", fontSize = 10.sp, color = Color.Red.copy(alpha = 0.5f)) }
                                                else if (downloaded) { Spacer(Modifier.width(8.dp)); Text("✓ Ready", fontSize = 10.sp, color = Color.Gray) }
                                                else { Spacer(Modifier.width(8.dp)); Text("↓ Download", fontSize = 11.sp, color = Color.Gray) }
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
                // Share-to-Hush tip — shown once after 3rd response
                val tipPrefs = chatContext.getSharedPreferences("offlineai", 0)
                val responseCount = tipPrefs.getInt("response_count", 0)
                var showTip by remember { mutableStateOf(responseCount >= 3 && !tipPrefs.getBoolean("share_tip_shown", false)) }
                if (showTip) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF6C63FF).copy(alpha = 0.08f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6C63FF).copy(alpha = 0.15f)),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("💡", fontSize = 16.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("Tip: Share text from Gmail, Chrome, or WhatsApp into Hush AI for instant help. Just tap Share → Hush AI.", fontSize = 12.sp, color = Color(0xFF6C63FF).copy(alpha = 0.8f), modifier = Modifier.weight(1f))
                            TextButton(onClick = { showTip = false; tipPrefs.edit().putBoolean("share_tip_shown", true).apply() }) {
                                Text("Got it", fontSize = 11.sp, color = Color(0xFF6C63FF))
                            }
                        }
                    }
                }
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
                                val allChips = remember {
                                    val base = listOf(
                                        "📄" to "Explain a PDF or document",
                                        "🌍" to "Translate to Japanese",
                                        "✉️" to "Help me write an email",
                                        "🔒" to "Explain my lease agreement",
                                        "💊" to "Explain my blood test results",
                                        "🌍" to "How to say 'thank you' in Arabic",
                                    )
                                    val actionChips = if (deviceRam >= 6) listOf(
                                        "💬" to "Text Mom on WhatsApp",
                                        "📞" to "Call the doctor",
                                        "✉️" to "Email my landlord about the heater",
                                        "📅" to "Add dentist appointment Friday 2pm",
                                    ) else emptyList()
                                    (base + actionChips).shuffled().take(4)
                                }
                                allChips.forEach { (icon, label) ->
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

                // Permission request card (shown in-chat when needed)
                if (needsPermission != null) {
                    PermissionRequestCard(
                        actionType = needsPermission!!,
                        onAllow = {
                            val perm = PermissionGate.requiredPermission(needsPermission!!)
                            if (perm != null) permissionLauncher.launch(perm)
                        },
                        onDeny = { needsPermission = null; pendingAction = null }
                    )
                }

                // Action confirmation card (shown after permission granted)
                if (pendingAction != null && needsPermission == null) {
                    ActionCard(
                        action = pendingAction!!,
                        contactName = resolvedContact?.name,
                        contactPhone = resolvedContact?.phone,
                        onConfirm = {
                            val action = pendingAction!!
                            val body = editedBody.ifBlank { action.body }
                            try {
                                when (action.type) {
                                    ActionType.MESSAGE -> {
                                        val phone = resolvedContact?.phone ?: ""
                                        if (action.app == "sms") IntentLauncher.sendSms(chatContext, phone, body)
                                        else IntentLauncher.sendWhatsApp(chatContext, phone, body)
                                    }
                                    ActionType.CALL -> IntentLauncher.dial(chatContext, resolvedContact?.phone ?: "")
                                    ActionType.EMAIL -> IntentLauncher.sendEmail(chatContext, action.to, action.subject, body)
                                    ActionType.CALENDAR -> {
                                        IntentLauncher.addCalendarEvent(chatContext, action.title, action.date, action.time)
                                    }
                                }
                            } catch (e: Exception) {
                                Toast.makeText(chatContext, "Couldn't open app: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                            pendingAction = null; resolvedContact = null
                        },
                        onEdit = { editedBody = it },
                        onDismiss = { pendingAction = null; resolvedContact = null }
                    )
                }

                HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.5f))

                // Attached file chip — shows above input when a file is loaded
                if (attachedName.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF6C63FF).copy(alpha = 0.1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6C63FF).copy(alpha = 0.2f))
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("📄", fontSize = 14.sp)
                                Spacer(Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f, fill = false)) {
                                    Text(
                                        attachedName,
                                        fontSize = 12.sp,
                                        color = Color(0xFF6C63FF),
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    if (attachedLoading) {
                                        Text("Reading...", fontSize = 10.sp, color = Color.Gray)
                                    } else if (attachedTruncated) {
                                        Text("Partially loaded · switch to Genius for more", fontSize = 10.sp, color = Color(0xFFFBBC04))
                                    }
                                }
                                IconButton(
                                    onClick = { clearAttachment() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Close, "Remove file", tint = Color.Gray, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (modelTier != "swift") { IconButton(onClick = { filePicker.launch(arrayOf("application/pdf", "text/plain", "text/html", "text/csv", "text/markdown", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword")) }) { Text("📎", fontSize = 20.sp) } }
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
                                // Detect action intent from user input (deterministic, no model needed)
                                pendingUserAction = if (IntentRouter.isReady()) {
                                    IntentRouter.classify(userMsg)
                                } else null
                                // Capture attachment state NOW, clear chip immediately
                                val sendAttachedText = attachedText
                                val sendAttachedName = attachedName
                                clearAttachment()
                                messages = messages + Message(if (sendAttachedText != null) "📄 $sendAttachedName\n$userMsg" else userMsg, isUser = true)
                                isGenerating = true
                                scope.launch {
                                    listState.animateScrollToItem(maxOf(0, messages.size - 1))
                                    messages = messages + Message("Loading AI...", isUser = false, isStreaming = true)
                                    val modelPath = llmEngine.getModelPath(modelTier)
                                    if (modelPath != null) {
                                        val engine = currentEngine ?: InferenceEngineV2(chatContext, deviceRam)
                                        currentEngine = engine
                                        val fullTextBuilder = StringBuilder()
                                        var lastUiUpdate = 0L
                                        engine.loadModel(modelPath,
                                            onLoaded = {
                                                scope.launch(Dispatchers.Main) { messages = messages.dropLast(1) + Message("Thinking...", isUser = false, isStreaming = true) }
                                                val history = messages.filter { !it.isStreaming && it.content != "Loading AI..." && it.content != "Thinking..." }.dropLast(1).map { m ->
                                                    if (m.isUser) "user" to m.content else "assistant" to m.content
                                                }
                                val promptWithContext = if (sendAttachedText != null) {
                                                    val docContext = "Context from document '$sendAttachedName':\n${sendAttachedText}\n\n---\nUser question: $userMsg"
                                                    docContext
                                                } else if (sharedContent != null) {
                                                    val shared = sharedContent!!
                                                    sharedContent = null
                                                    "Shared text:\n${shared.take(docCharBudget)}\n\n---\nUser request: $userMsg"
                                                } else { userMsg }
                                                engine.generate(promptWithContext, history = history, tier = modelTier, installedApps = installedApps,
                                                    onToken = { token ->
                                                        fullTextBuilder.append(token)
                                                        val now = System.currentTimeMillis()
                                                        if (now - lastUiUpdate > 50) { // throttle UI to 20fps max
                                                            lastUiUpdate = now
                                                            val text = fullTextBuilder.toString()
                                                            scope.launch(Dispatchers.Main) {
                                                                messages = messages.dropLast(1) + Message(text, isUser = false, isStreaming = true)
                                                                listState.animateScrollToItem(maxOf(0, messages.size - 1))
                                                            }
                                                        }
                                                    },
                                                    onDone = {
                                                        scope.launch(Dispatchers.Main) {
                                                            // Clean model output
                                                            val cleanText = fullTextBuilder.toString().trimEnd()
                                                            messages = messages.dropLast(1) + Message(cleanText, isUser = false)

                                                            val detected = pendingUserAction
                                                            pendingUserAction = null

                                                            if (detected != null) {
                                                                val action = if (detected.type == ActionType.EMAIL && detected.body.isBlank()) {
                                                                    detected.copy(body = cleanText)
                                                                } else detected

                                                                // For MESSAGE/CALL: validate contact exists BEFORE showing card
                                                                if (action.type == ActionType.MESSAGE || action.type == ActionType.CALL) {
                                                                    if (!PermissionGate.hasPermission(chatContext, action.type)) {
                                                                        pendingAction = action
                                                                        editedBody = action.body
                                                                        needsPermission = action.type
                                                                    } else {
                                                                        resolvedContact = try { ContactResolver.resolve(chatContext, action.contact) } catch (_: Exception) { null }
                                                                        if (resolvedContact != null) {
                                                                            // Contact found — show action card
                                                                            pendingAction = action
                                                                            editedBody = action.body
                                                                        }
                                                                        // Contact NOT found — silently skip action card, just show AI response
                                                                        // No "couldn't find" message — avoids false positive embarrassment
                                                                    }
                                                                } else {
                                                                    // EMAIL and CALENDAR don't need contact validation
                                                                    pendingAction = action
                                                                    editedBody = action.body
                                                                }
                                                            }

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
