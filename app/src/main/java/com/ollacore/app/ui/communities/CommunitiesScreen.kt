package com.ollacore.app.ui.communities

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ollacore.app.ui.home.WaBg
import com.ollacore.app.ui.home.WaGreen
import com.ollacore.app.ui.home.WaSub
import com.ollacore.app.ui.home.WaText

/**
 * Communities tab (reference nav). Group-of-groups needs the backend
 * Communities service (see OLLACORE-BACKEND-SPEC.txt) - honest placeholder
 * in reference dark styling, no faked groups.
 */
@Composable
fun CommunitiesContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WaBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(WaGreen.copy(alpha = 0.15f))
            ) {
                Icon(Icons.Default.Groups, contentDescription = null, tint = WaGreen,
                    modifier = Modifier.size(44.dp))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("Communities", color = WaText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Bring related groups together with announcements. " +
                    "Needs the Communities backend service - see OLLACORE-BACKEND-SPEC.txt.",
                color = WaSub,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}
