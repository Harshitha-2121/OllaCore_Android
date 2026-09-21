package com.ollacore.app.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ollacore.app.ui.theme.OLLACORE_TAGLINE
import com.ollacore.app.ui.theme.OllacoreLogo
import com.ollacore.app.ui.theme.SplashGradient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Splash: full-brand gradient surface (allowed gradient use) with the
 * Ollacore logo, wordmark and "Connect. Chat. Share." tagline.
 *
 * Entrance (spec section 5): fade in + scale 0.85 -> 1 + slight rise,
 * ~850ms total - quick, premium, never in the way.
 */
@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    // 0 -> 1 entrance progress driving alpha, scale and rise together.
    val entrance = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            entrance.animateTo(1f, animationSpec = tween(durationMillis = 850))
        }
        delay(1100)
        onTimeout()
    }

    val scale = 0.85f + 0.15f * entrance.value
    val risePx = with(LocalDensity.current) { (24.dp * (1f - entrance.value)).toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplashGradient),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .alpha(entrance.value)
                .scale(scale)
                .graphicsLayer { translationY = risePx }
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color.White.copy(alpha = 0.16f))
                    .padding(24.dp)
            ) {
                OllacoreLogo(size = 104.dp)
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "Ollacore",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                OLLACORE_TAGLINE,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.85f)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .alpha(entrance.value)
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "End-to-end encrypted",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}
