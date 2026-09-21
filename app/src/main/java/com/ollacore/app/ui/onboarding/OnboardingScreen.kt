package com.ollacore.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ollacore.app.ui.theme.BrandGradient
import com.ollacore.app.ui.theme.GradientButton
import com.ollacore.app.ui.theme.HeroWash
import com.ollacore.app.ui.theme.OLLACORE_TAGLINE
import com.ollacore.app.ui.theme.OllaPrimaryBlue
import com.ollacore.app.ui.theme.OllacoreLogo
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val title: String,
    val subtitle: String,
    val mainIcon: ImageVector,
    val chips: List<ImageVector>
)

private val Pages = listOf(
    OnboardingPage(
        title = "Stay Connected",
        subtitle = "Chat, call and share with your friends, family and teams.",
        mainIcon = Icons.Default.ChatBubble,
        chips = listOf(Icons.Default.Call, Icons.Default.Image)
    ),
    OnboardingPage(
        title = "Secure & Private",
        subtitle = "Your conversations are protected with end-to-end encryption.",
        mainIcon = Icons.Default.Lock,
        chips = listOf(Icons.Default.VerifiedUser)
    ),
    OnboardingPage(
        title = "Everything in One Place",
        subtitle = "Messages, calls, media and communities in one simple experience.",
        mainIcon = Icons.Default.Dashboard,
        chips = listOf(Icons.Default.ChatBubble, Icons.Default.Call, Icons.Default.Image)
    )
)

/**
 * Onboarding (allowed gradient surface): 3 pager screens with original
 * Compose-drawn illustrations, page indicators, primary CTA + log-in path.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { Pages.size })
    val scope = rememberCoroutineScope()
    val lastPage = pagerState.currentPage == Pages.size - 1

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HeroWash)
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OllacoreLogo(size = 44.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    "Ollacore",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    OLLACORE_TAGLINE,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { index ->
            OnboardingPageContent(page = Pages[index])
        }

        // Page indicators.
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            repeat(Pages.size) { index ->
                val selected = index == pagerState.currentPage
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(width = if (selected) 28.dp else 8.dp, height = 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        GradientButton(
            text = if (lastPage) "Get Started" else "Next",
            onClick = {
                if (lastPage) {
                    onDone()
                } else {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        TextButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Already have an account? Log in")
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        // Original illustration: gradient tile with hero icon + floating chips.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(200.dp)
                    .clip(RoundedCornerShape(48.dp))
                    .background(BrandGradient)
            ) {
                Icon(
                    page.mainIcon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(88.dp)
                )
            }
            page.chips.forEachIndexed { index, chip ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(if (index % 2 == 0) Alignment.TopEnd else Alignment.BottomStart)
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Icon(
                            chip,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            page.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            page.subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}
