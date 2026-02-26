package app.hushai.android

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon

@Composable
fun OnboardingScreen(onGetStarted: () -> Unit) {
    val dark = Color(0xFF0A0A1A)
    val accent = Color(0xFF6C63FF)
    val accentGlow = Color(0xFF8B83FF)

    // Neon pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(2000, easing = EaseInOut), RepeatMode.Reverse),
        label = "glowAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(dark, Color(0xFF0D0D24))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Lock with neon glow using real blur
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(120.dp)
                    .drawBehind {
                        val paint = android.graphics.Paint().apply {
                            color = android.graphics.Color.argb(
                                (glowAlpha * 255).toInt(), 130, 80, 255
                            )
                            maskFilter = android.graphics.BlurMaskFilter(
                                100f, android.graphics.BlurMaskFilter.Blur.NORMAL
                            )
                        }
                        drawContext.canvas.nativeCanvas.drawCircle(
                            size.width / 2f, size.height / 2f, 90f, paint
                        )
                        // Inner brighter glow
                        paint.maskFilter = android.graphics.BlurMaskFilter(
                            60f, android.graphics.BlurMaskFilter.Blur.NORMAL
                        )
                        paint.color = android.graphics.Color.argb(
                            (glowAlpha * 255).toInt(), 160, 100, 255
                        )
                        drawContext.canvas.nativeCanvas.drawCircle(
                            size.width / 2f, size.height / 2f, 60f, paint
                        )
                    }
            ) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = "Private",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                "Hush AI",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(
                    shadow = Shadow(color = accentGlow.copy(alpha = glowAlpha), offset = Offset.Zero, blurRadius = 24f)
                ),
                color = Color.White
            )

            Spacer(Modifier.height(6.dp))

            Text(
                "Private AI on your device",
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.5f)
            )

            Spacer(Modifier.height(48.dp))

            PrivacyPoint("🔒", "Fully offline", "Works without internet after setup")
            Spacer(Modifier.height(16.dp))
            PrivacyPoint("🚫", "Zero data collection", "Nothing leaves your device. Ever.")
            Spacer(Modifier.height(16.dp))
            PrivacyPoint("🗑️", "You're in control", "Delete everything anytime, no trace left")

            Spacer(Modifier.height(48.dp))

            Button(
                onClick = onGetStarted,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent)
            ) {
                Text("Get Started", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "Free forever · No account · No tracking",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.35f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun PrivacyPoint(icon: String, title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 26.sp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(subtitle, color = Color.White.copy(alpha = 0.45f), fontSize = 13.sp)
        }
    }
}
