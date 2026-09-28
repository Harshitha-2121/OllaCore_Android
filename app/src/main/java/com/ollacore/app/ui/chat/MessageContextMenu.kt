package com.ollacore.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ollacore.app.data.model.MessageResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Dark popup tokens (reference look: near-black cards in both app themes). */
private val PopupCard = Color(0xFF1C1C1E)
private val PopupText = Color.White
private val PopupSubtle = Color.White.copy(alpha = 0.12f)
private val PopupDanger = Color(0xFFFF8A9E)

/** Top-level menu entries in reference order. */
enum class MenuActionId { REPLY, FORWARD, COPY, INFO, STAR, DELETE, MORE }

/** Overflow entries behind "More...". */
enum class MoreActionId { SELECT, EDIT }

/** Reference reaction set + expandable extras for the "+" flow. */
val CONTEXT_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏", "🥹")
private val EXTRA_REACTIONS = listOf("😀", "😍", "🤔", "👏", "🔥", "🎉", "😭", "😳")

/**
 * Pure visibility rules (unit-tested): deleted messages expose Info only;
 * Copy needs text; Delete stays own-message only; everything else universal.
 */
fun menuActionsFor(isOwn: Boolean, hasText: Boolean, isDeleted: Boolean): List<MenuActionId> {
    if (isDeleted) return listOf(MenuActionId.INFO)
    return buildList {
        add(MenuActionId.REPLY)
        add(MenuActionId.FORWARD)
        if (hasText) add(MenuActionId.COPY)
        add(MenuActionId.INFO)
        add(MenuActionId.STAR)
        if (isOwn) add(MenuActionId.DELETE)
        add(MenuActionId.MORE)
    }
}

/** Pure overflow rules (unit-tested): Edit stays own-text-only. */
fun moreActionsFor(isOwn: Boolean, isText: Boolean): List<MoreActionId> =
    buildList {
        add(MoreActionId.SELECT)
        if (isOwn && isText) add(MoreActionId.EDIT)
    }

/**
 * WhatsApp-style anchored context popup: reaction pill on top, vertical menu
 * below. Positioned from the selected message's window bounds (above it when
 * space is tight below, beside it otherwise) - never a hard-coded Y.
 * All actions delegate to the existing ChatScreen handlers; this file owns
 * only UI, positioning, and visibility rules.
 */
@Composable
fun MessageContextMenu(
    message: MessageResponse,
    isOwn: Boolean,
    isStarred: Boolean,
    status: MessageStatus?,
    hasText: Boolean,
    isText: Boolean,
    isDeleted: Boolean,
    anchor: Rect?,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReact: (String) -> Unit,
    onStar: () -> Unit,
    onSelect: () -> Unit
) {
    var showInfo by remember(message.id) { mutableStateOf(false) }
    var showMore by remember(message.id) { mutableStateOf(false) }
    var showExtraEmoji by remember(message.id) { mutableStateOf(false) }
    var visible by remember(message.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(message.id) { visible = true }
    fun close() {
        if (!visible) {
            onDismiss()
            return
        }
        scope.launch {
            visible = false
            delay(180)
            onDismiss()
        }
    }

    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val offset = remember(anchor, isOwn, config) {
        with(density) {
            val screenW = config.screenWidthDp.dp.toPx()
            val screenH = config.screenHeightDp.dp.toPx()
            val popupW = 260.dp.toPx()
            val estH = 430.dp.toPx()
            val margin = 12.dp.toPx()
            val topGuard = 72.dp.toPx()
            val bottomGuard = 120.dp.toPx()
            if (anchor == null) {
                IntOffset(
                    ((screenW - popupW) / 2).roundToInt(),
                    ((screenH - estH) / 2).roundToInt()
                )
            } else {
                val x = (if (isOwn) anchor.right - popupW else anchor.left)
                    .coerceIn(margin, (screenW - popupW - margin).coerceAtLeast(margin))
                val below = screenH - anchor.bottom
                val rawY = if (below > estH + margin) {
                    anchor.bottom + 8.dp.toPx()
                } else {
                    anchor.top - estH - 8.dp.toPx()
                }
                val y = rawY.coerceIn(
                    topGuard,
                    (screenH - estH - bottomGuard).coerceAtLeast(topGuard)
                )
                IntOffset(x.roundToInt(), y.roundToInt())
            }
        }
    }

    Popup(
        offset = offset,
        onDismissRequest = { close() },
        properties = PopupProperties(focusable = true)
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(180)) + scaleIn(tween(200), initialScale = 0.92f) +
                slideInVertically(tween(200)) { it / 12 },
            exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.95f)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(260.dp)
            ) {
                if (!isDeleted) {
                    ReactionBar(
                        onReact = { emoji -> onReact(emoji); close() },
                        showExtra = showExtraEmoji,
                        onToggleExtra = { showExtraEmoji = !showExtraEmoji }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                MenuCard(
                    message = message,
                    isOwn = isOwn,
                    isStarred = isStarred,
                    hasText = hasText,
                    isText = isText,
                    isDeleted = isDeleted,
                    onReply = { onReply(); close() },
                    onForward = { onForward(); close() },
                    onCopy = { onCopy(); close() },
                    onInfo = { showInfo = true },
                    onStar = { onStar(); close() },
                    onDelete = onDelete,
                    onMore = { showMore = true },
                    onSelect = { onSelect(); close() },
                    onEdit = { onEdit(); close() },
                    showMoreSheet = showMore,
                    onDismissMore = { showMore = false }
                )
            }
        }
    }

    if (showInfo) {
        InfoDialog(message = message, status = status, onDismiss = { showInfo = false })
    }
}

@Composable
private fun ReactionBar(
    onReact: (String) -> Unit,
    showExtra: Boolean,
    onToggleExtra: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = PopupCard,
            shadowElevation = 8.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                CONTEXT_REACTIONS.forEach { emoji ->
                    Text(
                        emoji,
                        fontSize = 26.sp,
                        modifier = Modifier
                            .clickable(onClick = { onReact(emoji) })
                            .padding(horizontal = 4.dp)
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(30.dp)
                        .border(1.5.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                        .clickable(onClick = onToggleExtra)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "More reactions",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        if (showExtra) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(shape = CircleShape, color = PopupCard, shadowElevation = 8.dp) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    EXTRA_REACTIONS.forEach { emoji ->
                        Text(
                            emoji,
                            fontSize = 24.sp,
                            modifier = Modifier
                                .clickable(onClick = { onReact(emoji) })
                                .padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuCard(
    message: MessageResponse,
    isOwn: Boolean,
    isStarred: Boolean,
    hasText: Boolean,
    isText: Boolean,
    isDeleted: Boolean,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onCopy: () -> Unit,
    onInfo: () -> Unit,
    onStar: () -> Unit,
    onDelete: () -> Unit,
    onMore: () -> Unit,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    showMoreSheet: Boolean,
    onDismissMore: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = PopupCard,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            menuActionsFor(isOwn, hasText, isDeleted).forEach { action ->
                when (action) {
                    MenuActionId.REPLY -> MenuRow(
                        Icons.AutoMirrored.Filled.Reply, "Reply", onReply
                    )
                    MenuActionId.FORWARD -> MenuRow(
                        Icons.AutoMirrored.Filled.Forward, "Forward", onForward
                    )
                    MenuActionId.COPY -> MenuRow(
                        Icons.Default.ContentCopy, "Copy", onCopy
                    )
                    MenuActionId.INFO -> MenuRow(
                        Icons.Default.Info, "Info", onInfo
                    )
                    MenuActionId.STAR -> MenuRow(
                        if (isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                        if (isStarred) "Unstar" else "Star",
                        onStar
                    )
                    MenuActionId.DELETE -> MenuRow(
                        Icons.Default.DeleteOutline, "Delete", onDelete, danger = true
                    )
                    MenuActionId.MORE -> {
                        HorizontalDivider(
                            color = PopupSubtle,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                        MenuRow(
                            icon = {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(26.dp)
                                        .border(1.5.dp, PopupText, CircleShape)
                                ) {
                                    Icon(
                                        Icons.Default.MoreHoriz,
                                        contentDescription = null,
                                        tint = PopupText,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            },
                            label = "More...",
                            onClick = onMore
                        )
                    }
                }
            }
        }
    }

    if (showMoreSheet) {
        MoreDialog(
            actions = moreActionsFor(isOwn, isText),
            onSelect = { onSelect(); onDismissMore() },
            onEdit = { onEdit(); onDismissMore() },
            onDismiss = onDismissMore
        )
    }
}

@Composable
private fun MenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    MenuRow(
        icon = {
            Icon(
                icon,
                contentDescription = null,
                tint = if (danger) PopupDanger else PopupText,
                modifier = Modifier.size(22.dp)
            )
        },
        label = label,
        onClick = onClick,
        danger = danger
    )
}

@Composable
private fun MenuRow(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .heightIn(min = 40.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(26.dp)) {
            icon()
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            label,
            color = if (danger) PopupDanger else PopupText,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun InfoDialog(
    message: MessageResponse,
    status: MessageStatus?,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = PopupCard) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                MessageInfoRow("Sent", messageTime(message.createdAt) ?: "—")
                MessageInfoRow(
                    "Status",
                    when (status) {
                        MessageStatus.SENDING -> "Sending…"
                        MessageStatus.SENT -> "Sent"
                        MessageStatus.DELIVERED -> "Delivered"
                        MessageStatus.READ -> "Read"
                        MessageStatus.FAILED -> "Failed - use Retry in chat"
                        null -> "Received"
                    }
                )
                if (message.editedAt != null) MessageInfoRow("Edited", "Yes")
                MessageInfoRow("Type", message.kind)
            }
        }
    }
}

@Composable
private fun MoreDialog(
    actions: List<MoreActionId>,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = PopupCard) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                actions.forEach { action ->
                    when (action) {
                        MoreActionId.SELECT -> MenuRow(
                            Icons.Default.Checklist, "Select", onSelect
                        )
                        MoreActionId.EDIT -> MenuRow(
                            Icons.Default.Edit, "Edit", onEdit
                        )
                    }
                }
            }
        }
    }
}
