package app.hushai.android

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DownloadScreen(
    tierId: String,
    downloadState: DownloadState,
    onStartDownload: () -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit = {}
) {
    val dark = Color(0xFF1A1A2E)
    val model = MODEL_TIERS.firstOrNull { it.id == tierId }
    val modelInfo = MODELS[tierId]

    LaunchedEffect(Unit) { onStartDownload() }
    LaunchedEffect(downloadState) {
        if (downloadState is DownloadState.Complete) {
            kotlinx.coroutines.delay(1000)
            onComplete()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(dark, Color(0xFF16213E)))),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (downloadState) {
                is DownloadState.Idle, is DownloadState.Downloading -> {
                    val progress = if (downloadState is DownloadState.Downloading) downloadState.progress else 0f
                    val dlMB = if (downloadState is DownloadState.Downloading) downloadState.downloadedMB else 0
                    val totalMB = model?.sizeMB ?: 0

                    Text(model?.emoji ?: "🤫", fontSize = 48.sp)
                    Spacer(Modifier.height(20.dp))
                    Text("Downloading ${model?.name ?: "AI"}", fontSize = 22.sp,
                        fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text("This only happens once", fontSize = 14.sp, color = Color.White.copy(alpha = 0.5f))
                    Spacer(Modifier.height(32.dp))

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = Color(0xFF6C63FF),
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "${dlMB} MB / ${totalMB} MB  ·  ${(progress * 100).toInt()}%",
                        fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "☕ Grab a coffee — your private AI is being set up.\nAfter this, everything runs offline.",
                        fontSize = 13.sp, color = Color.White.copy(alpha = 0.4f),
                        textAlign = TextAlign.Center, lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(24.dp))
                    TextButton(onClick = onCancel) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)
                    }
                }
                is DownloadState.Complete -> {
                    Text("✅", fontSize = 48.sp)
                    Spacer(Modifier.height(20.dp))
                    Text("Ready!", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text("Your AI is set up and ready to go", fontSize = 14.sp, color = Color.White.copy(alpha = 0.5f))
                }
                is DownloadState.Error -> {
                    Text("❌", fontSize = 48.sp)
                    Spacer(Modifier.height(20.dp))
                    Text("Download failed", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text(downloadState.message, fontSize = 13.sp, color = Color(0xFFFF6B6B),
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onStartDownload,
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C63FF))
                    ) { Text("Try Again") }
                }
            }
        }
    }
}
