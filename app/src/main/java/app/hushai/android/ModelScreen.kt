package app.hushai.android

import android.os.StatFs
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ModelTier(
    val id: String, val emoji: String, val name: String, val tagline: String,
    val sizeMB: Int, val minRamGB: Int, val speed: String, val bestFor: String,
    val quality: Int
)

val MODEL_TIERS = listOf(
    ModelTier("swift", "⚡", "Swift", "Instant replies",
        533, 3, "~5 sec", "Quick answers, simple tasks. Like texting a smart friend.", 2),
    ModelTier("smart", "🎯", "Smart", "Best balance",
        1500, 4, "~8 sec", "Writing, summaries, analysis. Handles most tasks well.", 3),
    ModelTier("genius", "🧠", "Genius", "Most capable",
        2740, 6, "~12 sec", "Complex reasoning, math, detailed writing. Best everyday choice.", 4)
)

fun getAvailableStorageMB(): Long {
    val stat = StatFs(Environment.getDataDirectory().path)
    return stat.availableBlocksLong * stat.blockSizeLong / (1024 * 1024)
}

@Composable
fun ModelSelectionScreen(deviceRam: Int, onModelSelected: (String) -> Unit, isSettings: Boolean = false) {
    val context = LocalContext.current
    val storageMB = remember { getAvailableStorageMB() }
    val storageGB = storageMB / 1024f
    val recommended = remember {
        when {
            deviceRam >= 8 -> "genius"
            deviceRam >= 6 -> "smart"
            else -> "swift"
        }
    }
    var selected by remember { mutableStateOf(recommended) }
    val dark = Color(0xFF1A1A2E)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(dark, Color(0xFF16213E))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            if (isSettings) { Spacer(Modifier.height(8.dp)) }
            else { Spacer(Modifier.height(16.dp)) }

            Text(
                if (isSettings) "Change AI Model" else "Choose your AI",
                fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White
            )
            Spacer(Modifier.height(16.dp))

            // Device info card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White.copy(alpha = 0.08f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Your device", fontSize = 12.sp, color = Color.White.copy(alpha = 0.4f))
                    Spacer(Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        DeviceInfoItem("📱", "RAM", "${deviceRam} GB")
                        DeviceInfoItem("💾", "Free", String.format("%.1f GB", storageGB))
                        DeviceInfoItem("📲", "Phone", android.os.Build.MODEL)
                        DeviceInfoItem("✅", "Best fit",
                            when { deviceRam >= 8 -> "Genius"; deviceRam >= 6 -> "Smart"; else -> "Swift" })
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            MODEL_TIERS.forEach { tier ->
                val isSelected = selected == tier.id
                val canRun = deviceRam >= tier.minRamGB
                val isTight = canRun && deviceRam < tier.minRamGB + 2
                val hasStorage = storageMB >= tier.sizeMB
                val isAvailable = canRun && hasStorage

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .then(
                            if (isSelected) Modifier.border(2.dp, Color(0xFF6C63FF), RoundedCornerShape(16.dp))
                            else Modifier
                        )
                        .clickable(enabled = canRun) { selected = tier.id },
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSelected) Color.White.copy(alpha = 0.12f)
                           else if (!isAvailable) Color.White.copy(alpha = 0.03f)
                           else Color.White.copy(alpha = 0.06f)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tier.emoji, fontSize = 28.sp)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(tier.name, color = if (isAvailable) Color.White else Color.White.copy(alpha = 0.3f),
                                        fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Spacer(Modifier.width(8.dp))
                                    if (tier.id == recommended && isAvailable) {
                                        Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF6C63FF)) {
                                            Text(" Recommended ", fontSize = 10.sp, color = Color.White,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    }
                                }
                                Text(tier.tagline, color = Color.White.copy(alpha = if (isAvailable) 0.5f else 0.2f), fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.height(10.dp))

                        // Quality bar
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Quality ", fontSize = 11.sp, color = Color.White.copy(alpha = 0.4f))
                            repeat(5) { i ->
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 1.dp)
                                        .size(width = 16.dp, height = 6.dp)
                                        .background(
                                            if (i < tier.quality) Color(0xFF6C63FF).copy(alpha = if (isAvailable) 1f else 0.3f)
                                            else Color.White.copy(alpha = 0.1f),
                                            RoundedCornerShape(3.dp)
                                        )
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("Speed ", fontSize = 11.sp, color = Color.White.copy(alpha = 0.4f))
                            repeat(5) { i ->
                                val speedBars = 5 - tier.quality + 1
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 1.dp)
                                        .size(width = 16.dp, height = 6.dp)
                                        .background(
                                            if (i < speedBars) Color(0xFF34A853).copy(alpha = if (isAvailable) 1f else 0.3f)
                                            else Color.White.copy(alpha = 0.1f),
                                            RoundedCornerShape(3.dp)
                                        )
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            InfoChip(if (tier.sizeMB >= 1000) "${tier.sizeMB/1000} GB" else "${tier.sizeMB} MB")
                            InfoChip("${tier.minRamGB} GB+ RAM")
                            InfoChip(tier.speed)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(tier.bestFor, color = Color.White.copy(alpha = if (isAvailable) 0.4f else 0.2f), fontSize = 12.sp)

                        if (!canRun) {
                            Spacer(Modifier.height(6.dp))
                            Text("🚫 Your device has ${deviceRam}GB RAM — this model needs ${tier.minRamGB}GB+ and will likely crash. Try ${if (deviceRam >= 6) "Genius" else if (deviceRam >= 4) "Smart" else "Swift"} instead.",
                                fontSize = 11.sp, color = Color(0xFFFF6B6B), lineHeight = 16.sp)
                        } else if (isTight) {
                            Spacer(Modifier.height(6.dp))
                            Text("⚠️ Your device has ${deviceRam}GB RAM — this model may run slowly. Close other apps for best results.",
                                fontSize = 11.sp, color = Color(0xFFFBBC04), lineHeight = 16.sp)
                        } else if (!hasStorage) {
                            Spacer(Modifier.height(6.dp))
                            Text("⚠️ Needs ${if (tier.sizeMB >= 1000) "${tier.sizeMB/1000}GB" else "${tier.sizeMB}MB"} free space (you have ${String.format("%.1f", storageGB)}GB free). Free up space first.",
                                fontSize = 11.sp, color = Color(0xFFFF6B6B), lineHeight = 16.sp)
                        }
                }
            }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = { onModelSelected(selected) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C63FF))
            ) {
                val tier = MODEL_TIERS.first { it.id == selected }
                Text(
                    if (isSettings) "Switch to ${tier.name}" else "Download ${tier.name} (${if (tier.sizeMB >= 1000) "${tier.sizeMB/1000} GB" else "${tier.sizeMB} MB"})",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "One-time download · WiFi recommended · Can change anytime in settings",
                fontSize = 11.sp, color = Color.White.copy(alpha = 0.35f),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun DeviceInfoItem(icon: String, label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 18.sp)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Text(label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f))
    }
}

@Composable
fun InfoChip(text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = Color.White.copy(alpha = 0.08f)) {
        Text(text, fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}
