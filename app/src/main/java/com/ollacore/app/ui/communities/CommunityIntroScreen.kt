package com.ollacore.app.ui.communities

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ollacore.app.ui.theme.BrandGradient

/**
 * Reference community introduction (overflow "New community"): illustration,
 * title, description, Get started. Communities run on group chats until the
 * backend communities service lands (see OLLACORE-BACKEND-SPEC.txt), so Get
 * started opens the real group-creation flow - nothing is faked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityIntroScreen(
    onBack: () -> Unit,
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New community") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(BrandGradient)
            ) {
                Icon(
                    Icons.Default.Groups,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(56.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "Bring related groups together",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Communities connect several group chats under one roof with " +
                    "announcements for everyone. Until the communities backend " +
                    "service lands, a community starts as a real announcement " +
                    "group - member adds, messages and history all work today.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onGetStarted,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Get started")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
