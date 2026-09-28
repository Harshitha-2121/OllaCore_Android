package com.ollacore.app

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ollacore.app.auth.AuthViewModel
import com.ollacore.app.auth.AuthViewModel.AuthStep
import com.ollacore.app.ui.call.CallScreen
import com.ollacore.app.ui.call.CallViewModel
import com.ollacore.app.ui.calls.CallHistoryViewModel
import com.ollacore.app.ui.chat.ChatScreen
import com.ollacore.app.ui.chat.ChatViewModel
import com.ollacore.app.ui.contact.ContactInfoScreen
import com.ollacore.app.ui.contact.ContactInfoViewModel
import com.ollacore.app.ui.contacts.ContactDetailsScreen
import com.ollacore.app.ui.contacts.ContactsScreen
import com.ollacore.app.ui.contacts.ContactsViewModel
import com.ollacore.app.ui.contacts.NewChatScreen
import com.ollacore.app.ui.contacts.NewContactScreen
import com.ollacore.app.ui.devices.DevicesScreen
import com.ollacore.app.ui.docs.DocumentViewerScreen
import com.ollacore.app.ui.media.ImageViewerScreen
import com.ollacore.app.ui.devices.DevicesViewModel
import com.ollacore.app.ui.globalsearch.GlobalSearchScreen
import com.ollacore.app.ui.globalsearch.GlobalSearchViewModel
import com.ollacore.app.ui.groups.GroupInfoScreen
import com.ollacore.app.ui.groups.GroupInfoViewModel
import com.ollacore.app.ui.groups.NewGroupScreen
import com.ollacore.app.ui.groups.NewGroupViewModel
import com.ollacore.app.ui.home.HomeScreen
import com.ollacore.app.ui.home.HomeViewModel
import com.ollacore.app.ui.profile.ProfileScreen
import com.ollacore.app.auth.PhoneInputScreen
import com.ollacore.app.auth.OtpVerificationScreen
import com.ollacore.app.ui.onboarding.OnboardingScreen
import com.ollacore.app.ui.privacy.AppLockGate
import com.ollacore.app.ui.privacy.PrivacySecurityScreen
import com.ollacore.app.ui.privacy.PrivacySecurityViewModel
import com.ollacore.app.ui.search.SearchScreen
import com.ollacore.app.ui.settings.NotificationsSettingsScreen
import com.ollacore.app.ui.settings.NotificationsSettingsViewModel
import com.ollacore.app.ui.search.SearchViewModel
import com.ollacore.app.ui.splash.SplashScreen
import com.ollacore.app.ui.theme.OllacoreTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Spec 31: user theme (Blue/Green/Purple/Dark/System) drives the whole app.
            val appContext = LocalContext.current.applicationContext
            val themeStore = remember { com.ollacore.app.data.local.ThemeStore(appContext) }
            val themeMode by themeStore.mode.collectAsState(
                initial = com.ollacore.app.data.local.ThemeMode.DARK
            )
            // Edge-to-edge bars: keep status/nav icons readable in both
            // themes (dark icons on light, light icons on dark).
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val effectiveDark = remember(themeMode, systemDark) {
                com.ollacore.app.ui.theme.resolveDarkTheme(themeMode, systemDark)
            }
            SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    .apply {
                        isAppearanceLightStatusBars = !effectiveDark
                        isAppearanceLightNavigationBars = !effectiveDark
                    }
            }
            // Settings > Accessibility > Text size: app-wide font scaling.
            val settingsPrefs = remember(appContext) {
                com.ollacore.app.data.local.ChatPrefsStore(appContext)
            }
            val textScaleStr by settingsPrefs.customFlow(
                com.ollacore.app.data.local.ChatPrefsStore.SettingsKeys.TEXT_SCALE, "1.0"
            ).collectAsState(initial = "1.0")
            val baseDensity = LocalDensity.current
            val appDensity = remember(baseDensity, textScaleStr) {
                androidx.compose.ui.unit.Density(
                    baseDensity.density,
                    (baseDensity.fontScale * (textScaleStr.toFloatOrNull() ?: 1f)).coerceIn(0.5f, 2.5f)
                )
            }
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDensity provides appDensity
            ) {
            OllacoreTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OllacoreNavHost()
                }
            }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OllacoreNavHost() {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = viewModel()
    val authState by authViewModel.uiState.collectAsState()

    // Splash shows first (brand moment); step routing resumes after it completes.
    var splashDone by remember { mutableStateOf(false) }
    val onboardingDone by authViewModel.onboardingDone.collectAsState()

    val currentEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(authState.step, splashDone) {
        if (!splashDone) return@LaunchedEffect
        // Onboarding owns its own exit: step routing must not override it
        // (onTimeout sets splashDone=true AND navigates, which would otherwise
        // immediately re-route to the step destination).
        if (currentEntry?.destination?.route == "onboarding") return@LaunchedEffect
        when (authState.step) {
            AuthStep.OTP_VERIFICATION -> navController.navigate("otp") { popUpTo("phone") { inclusive = false } }
            AuthStep.AUTHENTICATED -> navController.navigate("home") { popUpTo(0) { inclusive = true } }
            AuthStep.PHONE_INPUT -> navController.navigate("phone") { popUpTo(0) { inclusive = true } }
        }
    }

    // Spec 25 app lock gate (CLIENT-ONLY device credential; session-scoped unlock).
    val appContext = LocalContext.current.applicationContext
    val lockPrefs = remember { com.ollacore.app.data.local.ChatPrefsStore(appContext) }
    val appLockOn by lockPrefs.appLock.collectAsState(initial = false)
    var unlocked by remember { mutableStateOf(false) }
    LaunchedEffect(appLockOn) { if (!appLockOn) unlocked = false }
    val lockLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) unlocked = true
    }

    // Push tap routing (data-only FCM):
    //   call   -> ringing call screen (works for 1-to-1 and group calls: room_id identifies the call)
    //   chat   -> correct chat screen (works for 1-to-1 and group notifications alike)
    // Background taps deliver extras on a fresh MainActivity; foreground taps re-target home first.
    val activity = LocalContext.current as? Activity
    var pushConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(authState.step, pushConsumed) {
        if (!pushConsumed && authState.step == AuthStep.AUTHENTICATED) {
            val intent = activity?.intent
            when {
                intent?.getBooleanExtra("open_call", false) == true -> {
                    intent.getStringExtra("room_id")?.let { targetRoomId ->
                        navController.navigate("call/$targetRoomId?incoming=true")
                    }
                    intent.removeExtra("open_call")
                    pushConsumed = true
                }
                intent?.getBooleanExtra("open_chat", false) == true -> {
                    intent.getStringExtra("room_id")?.let { targetRoomId ->
                        navController.navigate("chat/$targetRoomId")
                    }
                    intent.removeExtra("open_chat")
                    pushConsumed = true
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    NavHost(navController = navController, startDestination = "splash") {
        composable("splash") {
            // rememberUpdatedState: the timeout lambda must see the LATEST auth
            // values, not the ones captured at first composition (stale closure
            // sent fresh installs straight to phone, skipping onboarding).
            val latestOnboarding by rememberUpdatedState(onboardingDone)
            val latestStep by rememberUpdatedState(authState.step)
            SplashScreen(
                onTimeout = {
                    splashDone = true
                    // Fresh installs see onboarding once; everyone else follows the auth step.
                    if (latestOnboarding == false) {
                        navController.navigate("onboarding") {
                            popUpTo("splash") { inclusive = true }
                        }
                    } else {
                        when (latestStep) {
                            AuthStep.OTP_VERIFICATION -> navController.navigate("otp") {
                                popUpTo("splash") { inclusive = true }
                            }
                            AuthStep.AUTHENTICATED -> navController.navigate("home") {
                                popUpTo("splash") { inclusive = true }
                            }
                            AuthStep.PHONE_INPUT -> navController.navigate("phone") {
                                popUpTo("splash") { inclusive = true }
                            }
                        }
                    }
                }
            )
        }

        composable("onboarding") {
            OnboardingScreen(
                onDone = {
                    authViewModel.completeOnboarding()
                    navController.navigate("phone") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                }
            )
        }

        composable("phone") {
            PhoneInputScreen(
                phone = authState.phone,
                onPhoneChange = authViewModel::updatePhone,
                onSendOtp = authViewModel::requestOtp,
                isLoading = authState.isLoading,
                error = authState.error,
                onErrorDismiss = authViewModel::clearError
            )
        }

        composable("otp") {
            OtpVerificationScreen(
                phone = authState.phone,
                otpCode = authState.otpCode,
                onOtpChange = authViewModel::updateOtpCode,
                onVerify = authViewModel::verifyOtp,
                onBack = { navController.popBackStack() },
                isLoading = authState.isLoading,
                error = authState.error,
                onErrorDismiss = authViewModel::clearError,
                onResendOtp = authViewModel::requestOtp
            )
        }

        composable("home") {
            val homeViewModel: HomeViewModel = viewModel()
            val homeState by homeViewModel.uiState.collectAsState()
            val callHistoryViewModel: CallHistoryViewModel = viewModel()
            val callLog by callHistoryViewModel.callLog.collectAsState()
            // Returning from a chat re-reads the inbox so read/unread counts
            // (and the distinct-conversation badge) reflect what just happened.
            LaunchedEffect(Unit) { homeViewModel.refresh() }
            val homeContext = LocalContext.current
            val archivedRooms by remember(homeContext) {
                com.ollacore.app.data.local.ChatPrefsStore(homeContext.applicationContext).archivedRooms
            }.collectAsState(initial = emptySet())
            val pinnedRooms by remember(homeContext) {
                com.ollacore.app.data.local.ChatPrefsStore(homeContext.applicationContext).pinnedRooms
            }.collectAsState(initial = emptySet())
            val mutedRooms by remember(homeContext) {
                com.ollacore.app.data.local.ChatPrefsStore(homeContext.applicationContext).mutedRooms
            }.collectAsState(initial = emptySet())
            val selectedChatIds by homeViewModel.selectedIds.collectAsState()

            HomeScreen(
                uiState = homeState,
                onConversationClick = { roomId ->
                    // Opening an archived chat unarchives it (Close chat is reversible).
                    homeViewModel.markOpened(roomId)
                    navController.navigate("chat/$roomId")
                },
                onNewChat = { navController.navigate("newChat") },
                onProfile = { navController.navigate("profile") },
                onRefresh = { homeViewModel.refresh() },
                onSearch = { navController.navigate("globalSearch") },
                callLog = callLog,
                onCallBack = { entry ->
                    val peer = Uri.encode(entry.peerName.ifBlank { "Call" })
                    if (entry.audioOnly == true) {
                        navController.navigate("call/${entry.roomId}?audioOnly=true&peerName=$peer")
                    } else {
                        navController.navigate("call/${entry.roomId}?audioOnly=false&peerName=$peer")
                    }
                },
                onDeleteCallLog = { id -> callHistoryViewModel.delete(id) },
                onClearCallLog = { callHistoryViewModel.clearAll() },
                onDevices = { navController.navigate("devices") },
                onPrivacy = { navController.navigate("privacy") },
                onNotifications = { navController.navigate("notifSettings") },
                onNewGroup = { navController.navigate("newGroup") },
                onNewCommunity = {
                    // No community-creation backend exists yet: honest placeholder.
                    android.widget.Toast.makeText(
                        navController.context,
                        "Communities are coming soon",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                },
                onSettings = { navController.navigate("settings") },
                archivedRooms = archivedRooms,
                selectedIds = selectedChatIds,
                pinnedRooms = pinnedRooms,
                mutedRooms = mutedRooms,
                onToggleSelect = { homeViewModel.toggleChatSelect(it) },
                onClearSelection = { homeViewModel.clearChatSelection() },
                onSelectAll = {
                    homeViewModel.selectAllChats(
                        homeState.inbox.map { it.roomId } - archivedRooms
                    )
                },
                onTogglePin = { homeViewModel.togglePinSelected() },
                onToggleMute = { homeViewModel.toggleMuteSelected() },
                onToggleArchive = { homeViewModel.toggleArchiveSelected() },
                onDeleteSelected = { homeViewModel.deleteSelected() }
            )
        }

        composable("settings") {
            com.ollacore.app.ui.settings.SettingsRoot(
                onProfile = { navController.navigate("profile") },
                onDevices = { navController.navigate("devices") },
                onPrivacy = { navController.navigate("privacy") },
                onNotifications = { navController.navigate("notifSettings") },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            "chat/{roomId}",
            arguments = listOf(navArgument("roomId") { type = NavType.StringType })
        ) { backStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: return@composable
            val chatViewModel: ChatViewModel = viewModel()
            val chatState by chatViewModel.uiState.collectAsState()
            val menuState by chatViewModel.menuState.collectAsState()
            val routeScope = rememberCoroutineScope()
            val routeContext = LocalContext.current
            var showGroupCall by remember { mutableStateOf(false) }

            LaunchedEffect(roomId) {
                chatViewModel.joinRoom(roomId)
            }

            // Group-call sheet (menu entry): candidates + create + jump to call.
            if (showGroupCall) {
                com.ollacore.app.ui.chat.GroupCallSheet(
                    peerName = chatState.peerName ?: "Chat",
                    peerUserId = chatState.peerUserId,
                    onLoadCandidates = { chatViewModel.loadCallCandidates() },
                    onStart = { name, ids, done ->
                        chatViewModel.startGroupCall(name, ids) { newRoom ->
                            done(newRoom)
                            if (newRoom != null) {
                                showGroupCall = false
                                val label = Uri.encode(name.ifBlank { "Group call" })
                                navController.navigate("call/$newRoom?audioOnly=false&peerName=$label")
                            }
                        }
                    },
                    onDismiss = { showGroupCall = false }
                )
            }

            ChatScreen(
                uiState = chatState,
                roomId = roomId,
                onBack = { navController.popBackStack() },
                onSendMessage = { text ->
                    chatViewModel.sendMessage(text)
                },
                onEditMessage = { id, text ->
                    chatViewModel.editMessage(id, text)
                },
                onDeleteMessage = { id ->
                    chatViewModel.deleteMessage(id)
                },
                onAddReaction = { id, emoji ->
                    chatViewModel.addReaction(id, emoji)
                },
                onReply = { msg ->
                    chatViewModel.setReplyTo(msg)
                },
                onLoadMore = { beforeSeq ->
                    chatViewModel.loadMore(beforeSeq)
                },
                onTypingStarted = { chatViewModel.sendTypingStarted() },
                onTypingStopped = { chatViewModel.sendTypingStopped() },
                onSearch = {
                    val roomToken = chatState.roomToken ?: ""
                    navController.navigate("search/$roomId/$roomToken")
                },
                onForward = { msg -> chatViewModel.forwardMessage(msg) },
                onCopy = { _ -> },
                onToggleStar = { id -> chatViewModel.toggleStar(id) },
                onToggleSelect = { id -> chatViewModel.toggleSelect(id) },
                onClearSelection = { chatViewModel.clearSelection() },
                onVoiceCall = {
                    val peer = Uri.encode(chatState.peerName ?: "Call")
                    navController.navigate("call/$roomId?audioOnly=true&peerName=$peer")
                },
                onVideoCall = {
                    val peer = Uri.encode(chatState.peerName ?: "Call")
                    navController.navigate("call/$roomId?audioOnly=false&peerName=$peer")
                },
                onAcceptCall = { targetRoomId ->
                    chatViewModel.acceptIncomingCall()
                    navController.navigate("call/$targetRoomId?incoming=true")
                },
                onDeclineCall = { chatViewModel.declineIncomingCall() },
                onReconnect = { chatViewModel.joinRoom(roomId) },
                // Groups -> Group Info; 1-to-1 -> own Profile (peer profile follows same pattern)
                // Groups -> Group Info; 1-to-1 -> the PEER's contact screen
                // (never my own Profile - that was the View-info bug).
                onProfileClick = {
                    if (chatState.kind.equals("group", ignoreCase = true)) {
                        navController.navigate("groupInfo/$roomId")
                    } else {
                        val peer = Uri.encode(chatState.peerName ?: "Chat")
                        val phone = Uri.encode(chatState.peerPhone ?: "")
                        navController.navigate("contactInfo/$roomId?peerName=$peer&peerPhone=$phone")
                    }
                },
                onPickAttachment = { },
                onOpenCamera = { },
                attachmentUrls = chatState.attachmentUrls,
                isUploading = chatState.isUploading,
                uploadProgress = chatState.uploadProgress,
                uploadingFilename = chatState.uploadingFilename,
                uploadError = chatState.uploadError,
                onSendMedia = { file, mime, kind, caption ->
                    chatViewModel.uploadAndSendFile(file, mime, kind, caption)
                },
                onCancelUpload = { chatViewModel.cancelUpload() },
                onRetryUpload = { chatViewModel.retryUpload() },
                onShareContact = { name, phone ->
                    val card = if (phone.isNotBlank()) "👤 $name\n$phone" else "👤 $name"
                    chatViewModel.sendMessage(card)
                },
                onOpenDocument = { url, name, mime ->
                    navController.navigate(
                        "doc?url=${Uri.encode(url)}&name=${Uri.encode(name)}&mime=${Uri.encode(mime)}"
                    )
                },
                onOpenImage = { url ->
                    navController.navigate("imageViewer?url=${Uri.encode(url)}")
                },
                onStartRecord = { chatViewModel.startRecording() },
                onCancelRecord = { chatViewModel.cancelRecording() },
                onSendRecord = { chatViewModel.sendRecording() },
                onClearRecordError = { chatViewModel.clearRecordError() },
                onSendLocation = {
                    // Demo location (backend/API check required for kind=location).
                    // Replace with FusedLocationProviderClient lastLocation in production.
                    chatViewModel.sendLocation(12.9716, 77.5946, "📍 Shared location")
                },
                onResolveUrl = { aid -> chatViewModel.resolveAttachmentUrl(aid) },
                onForceResolveUrl = { aid -> chatViewModel.resolveAttachmentUrl(aid, force = true) },
                onClearUploadError = { chatViewModel.clearUploadError() },
                onRetryMessage = { msg -> chatViewModel.retryForMessage(msg) },
                onClearError = { chatViewModel.clearChatError() },
                onLoginExpired = {
                    authViewModel.logout()
                    navController.navigate("phone") { popUpTo(0) }
                },
                // ── 3-dot overflow menu wiring ──
                menuState = menuState,
                onEnterSelection = { chatViewModel.enterSelectionMode() },
                onToggleFavourite = { chatViewModel.toggleFavourite() },
                onMute = { chatViewModel.setMuteDuration(it) },
                onDisappearing = { chatViewModel.setDisappearingTtl(it) },
                onCreateList = { name, done -> chatViewModel.createChatList(name, done) },
                onToggleListMember = { name, member -> chatViewModel.setRoomInList(name, member) },
                onCloseChat = {
                    chatViewModel.setArchived(true) {
                        navController.navigate("home") { popUpTo(0) }
                    }
                },
                onSendCallLink = {
                    chatViewModel.sendCallLink { link ->
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, "Join my call: $link")
                        }
                        routeContext.startActivity(android.content.Intent.createChooser(send, "Share call link"))
                    }
                },
                onOpenGroupCall = { showGroupCall = true },
                onReport = { chatViewModel.submitReport(it) },
                onToggleBlock = { chatViewModel.toggleBlock() },
                onClearChat = { chatViewModel.clearChat() },
                onDeleteChat = {
                    routeScope.launch {
                        if (chatViewModel.deleteChat()) {
                            navController.navigate("home") { popUpTo(0) }
                        }
                    }
                }
            )
        }

        composable(
            "search/{roomId}/{roomToken}",
            arguments = listOf(
                navArgument("roomId") { type = NavType.StringType },
                navArgument("roomToken") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: return@composable
            val roomToken = backStackEntry.arguments?.getString("roomToken") ?: return@composable
            val searchViewModel: SearchViewModel = viewModel()
            val searchState by searchViewModel.uiState.collectAsState()

            LaunchedEffect(roomId, roomToken) {
                searchViewModel.setRoom(roomToken, roomId)
            }

            SearchScreen(
                uiState = searchState,
                onQueryChange = searchViewModel::updateQuery,
                onSearch = searchViewModel::searchImmediate,
                onResultClick = { message ->
                    // Navigate back to chat with the message highlighted
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
                onClear = searchViewModel::clearSearch
            )
        }

        composable("profile") {
            ProfileScreen(
                displayName = authState.displayName,
                phone = authState.phone,
                about = authState.about,
                avatarUrl = authState.avatarUrl,
                onBack = { navController.popBackStack() },
                onUpdateName = { name -> authViewModel.updateProfile(name, authState.about) },
                onUpdateProfile = { name, about -> authViewModel.updateProfile(name, about) },
                onLinkedDevices = { navController.navigate("devices") },
                onPrivacy = { navController.navigate("privacy") },
                onNotifications = { navController.navigate("notifSettings") },
                onLogout = {
                    authViewModel.logout()
                    navController.navigate("phone") {
                        popUpTo(0)
                    }
                }
            )
        }

        composable("contacts") {
            val contactsViewModel: ContactsViewModel = viewModel()
            val contactsState by contactsViewModel.uiState.collectAsState()

            ContactsScreen(
                state = contactsState,
                viewModel = contactsViewModel,
                onBack = { navController.popBackStack() },
                onOpenDetails = { row ->
                    navController.navigate("contactDetails/${Uri.encode(row.key)}")
                },
                onRefresh = { contactsViewModel.refresh() },
                onNewGroup = { navController.navigate("newGroup") },
                onOpenChat = { targetRoomId ->
                    navController.navigate("chat/$targetRoomId")
                }
            )
        }

        // "+" FAB flow: Existing screen -> New chat -> New contact -> Save.
        composable("newChat") {
            val newChatViewModel: ContactsViewModel = viewModel()
            val newChatState by newChatViewModel.uiState.collectAsState()

            NewChatScreen(
                state = newChatState,
                viewModel = newChatViewModel,
                onBack = { navController.popBackStack() },
                onNewGroup = { navController.navigate("newGroup") },
                onNewContact = { navController.navigate("newContact") },
                onNewCommunity = {
                    // No community-creation backend exists yet: honest placeholder.
                    android.widget.Toast.makeText(
                        navController.context,
                        "Communities are coming soon",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                },
                onOpenChat = { targetRoomId ->
                    navController.navigate("chat/$targetRoomId")
                },
                onOpenDetails = { row ->
                    navController.navigate("contactDetails/${Uri.encode(row.key)}")
                },
                onRefresh = { newChatViewModel.refresh() }
            )
        }

        composable("newContact") {
            val newContactViewModel: ContactsViewModel = viewModel()

            NewContactScreen(
                viewModel = newContactViewModel,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        composable(
            "contactDetails/{key}",
            arguments = listOf(
                navArgument("key") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val key = backStackEntry.arguments?.getString("key") ?: ""
            val detailsViewModel: ContactsViewModel = viewModel()
            val detailsState by detailsViewModel.uiState.collectAsState()
            val row = detailsState.rows.find { it.key == key }
            if (row == null) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Contact details") },
                            navigationIcon = {
                                IconButton(onClick = { navController.popBackStack() }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back"
                                    )
                                }
                            }
                        )
                    }
                ) { padding ->
                    Box(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                ContactDetailsScreen(
                    row = row,
                    viewModel = detailsViewModel,
                    onBack = { navController.popBackStack() },
                    onOpenChat = { targetRoomId ->
                        navController.navigate("chat/$targetRoomId")
                    },
                    onVoiceCall = { roomId, peer ->
                        navController.navigate("call/$roomId?audioOnly=true&peerName=${Uri.encode(peer)}")
                    },
                    onVideoCall = { roomId, peer ->
                        navController.navigate("call/$roomId?audioOnly=false&peerName=${Uri.encode(peer)}")
                    },
                    onDeleted = { navController.popBackStack() }
                )
            }
        }

        composable("newGroup") {
            val newGroupViewModel: NewGroupViewModel = viewModel()
            val newGroupState by newGroupViewModel.uiState.collectAsState()

            // Created -> open the new group chat (Category 1 - create group API YES)
            LaunchedEffect(newGroupState.createdRoomId) {
                newGroupState.createdRoomId?.let { targetRoomId ->
                    newGroupViewModel.consumeCreated()
                    navController.navigate("chat/$targetRoomId") {
                        popUpTo("newGroup") { inclusive = true }
                    }
                }
            }

            NewGroupScreen(
                uiState = newGroupState,
                onToggleSelect = newGroupViewModel::toggleSelect,
                onNext = newGroupViewModel::goDetails,
                onBackStep = newGroupViewModel::backToSelect,
                onBack = { navController.popBackStack() },
                onNameChange = newGroupViewModel::updateName,
                onDescriptionChange = newGroupViewModel::updateDescription,
                onReview = newGroupViewModel::goReview,
                onBackToDetails = newGroupViewModel::backToDetails,
                onCreate = { file, mime -> newGroupViewModel.createGroup(file, mime) }
            )
        }

        composable("devices") {
            val devicesViewModel: DevicesViewModel = viewModel()
            val devicesState by devicesViewModel.uiState.collectAsState()

            DevicesScreen(
                uiState = devicesState,
                onBack = { navController.popBackStack() },
                onRefresh = { devicesViewModel.refresh() },
                onRegisterThis = { devicesViewModel.registerThisDevice() },
                onLogoutDevice = { pushToken -> devicesViewModel.logoutDevice(pushToken) },
                onClearTransient = { devicesViewModel.clearTransient() }
            )
        }

        composable("notifSettings") {
            val notifViewModel: NotificationsSettingsViewModel = viewModel()
            val notifState by notifViewModel.uiState.collectAsState()

            NotificationsSettingsScreen(
                uiState = notifState,
                onSet = { key, value -> notifViewModel.set(key, value) },
                onBack = { navController.popBackStack() }
            )
        }

        composable("privacy") {
            val privacyViewModel: PrivacySecurityViewModel = viewModel()
            val privacyState by privacyViewModel.uiState.collectAsState()

            PrivacySecurityScreen(
                uiState = privacyState,
                onSetAppLock = { enabled -> privacyViewModel.setAppLock(enabled) },
                onSetSecurityNotifications = { enabled -> privacyViewModel.setSecurityNotifications(enabled) },
                onBack = { navController.popBackStack() }
            )
        }

        composable("globalSearch") {
            val globalSearchViewModel: GlobalSearchViewModel = viewModel()
            val globalSearchState by globalSearchViewModel.uiState.collectAsState()
            val recents by globalSearchViewModel.recentSearches.collectAsState()

            GlobalSearchScreen(
                uiState = globalSearchState,
                onQueryChange = globalSearchViewModel::updateQuery,
                onSearch = globalSearchViewModel::searchNow,
                onTab = globalSearchViewModel::setTab,
                onOpenChat = { targetRoomId -> navController.navigate("chat/$targetRoomId") },
                onBack = { navController.popBackStack() },
                onClear = globalSearchViewModel::clear,
                recentSearches = recents,
                onRecentClick = { q ->
                    globalSearchViewModel.updateQuery(q)
                    globalSearchViewModel.searchNow()
                },
                onClearRecents = { globalSearchViewModel.clearRecents() }
            )
        }

        composable(
            "doc?url={url}&name={name}&mime={mime}",
            arguments = listOf(
                navArgument("url") { type = NavType.StringType; defaultValue = "" },
                navArgument("name") { type = NavType.StringType; defaultValue = "Document" },
                navArgument("mime") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            DocumentViewerScreen(
                downloadUrl = backStackEntry.arguments?.getString("url") ?: "",
                filename = backStackEntry.arguments?.getString("name") ?: "Document",
                mime = backStackEntry.arguments?.getString("mime") ?: "",
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            "imageViewer?url={url}",
            arguments = listOf(
                navArgument("url") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            ImageViewerScreen(
                imageUrl = backStackEntry.arguments?.getString("url") ?: "",
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            "contactInfo/{roomId}?peerName={peerName}&peerPhone={peerPhone}",
            arguments = listOf(
                navArgument("roomId") { type = NavType.StringType },
                navArgument("peerName") { type = NavType.StringType; defaultValue = "Chat" },
                navArgument("peerPhone") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: return@composable
            val infoViewModel: ContactInfoViewModel = viewModel()
            val infoState by infoViewModel.uiState.collectAsState()
            val muted by infoViewModel.mutedFlow(roomId).collectAsState()

            LaunchedEffect(roomId) {
                infoViewModel.load(roomId)
            }

            val displayPeer = infoState.alias?.ifBlank { null }
                ?: infoState.peerName.ifBlank {
                    backStackEntry.arguments?.getString("peerName") ?: "Chat"
                }
            ContactInfoScreen(
                uiState = infoState,
                muted = muted,
                onBack = { navController.popBackStack() },
                onVoiceCall = {
                    navController.navigate("call/$roomId?audioOnly=true&peerName=${Uri.encode(displayPeer)}")
                },
                onVideoCall = {
                    navController.navigate("call/$roomId?audioOnly=false&peerName=${Uri.encode(displayPeer)}")
                },
                onSearchChat = { token ->
                    navController.navigate("search/$roomId/$token")
                },
                onToggleMute = { checked -> infoViewModel.setMuted(roomId, checked) },
                onRename = { name -> infoViewModel.setAlias(roomId, name) },
                onBackToChat = { navController.popBackStack() },
                onOpenImage = { url ->
                    navController.navigate("imageViewer?url=${Uri.encode(url)}")
                },
                onOpenDocument = { url, name, mime ->
                    navController.navigate(
                        "doc?url=${Uri.encode(url)}&name=${Uri.encode(name)}&mime=${Uri.encode(mime)}"
                    )
                }
            )
        }

        composable(
            "groupInfo/{roomId}",
            arguments = listOf(navArgument("roomId") { type = NavType.StringType })
        ) { backStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: return@composable
            val groupInfoViewModel: GroupInfoViewModel = viewModel()
            val groupInfoState by groupInfoViewModel.uiState.collectAsState()

            LaunchedEffect(roomId) {
                groupInfoViewModel.load(roomId)
            }

            GroupInfoScreen(
                uiState = groupInfoState,
                onBack = { navController.popBackStack() },
                onRefresh = { groupInfoViewModel.refresh() },
                onRename = groupInfoViewModel::rename,
                onDescription = groupInfoViewModel::setDescription,
                onIcon = groupInfoViewModel::setIcon,
                onAddMember = groupInfoViewModel::addMember,
                onRemoveMember = groupInfoViewModel::removeMember,
                onSetAdmin = groupInfoViewModel::setAdmin,
                onInvite = groupInfoViewModel::createInvite,
                onLeave = groupInfoViewModel::leaveGroup,
                onClearTransient = groupInfoViewModel::clearTransient,
                onVoiceCall = {
                    val peer = Uri.encode(groupInfoState.name.ifBlank { "Group" })
                    navController.navigate("call/$roomId?audioOnly=true&peerName=$peer")
                },
                onVideoCall = {
                    val peer = Uri.encode(groupInfoState.name.ifBlank { "Group" })
                    navController.navigate("call/$roomId?audioOnly=false&peerName=$peer")
                },
                onSearchChat = {
                    groupInfoViewModel.roomTokenSnapshot()?.let { token ->
                        navController.navigate("search/$roomId/$token")
                    }
                },
                onToggleMute = { groupInfoViewModel.toggleMute() },
                onLeft = {
                    navController.navigate("home") { popUpTo(0) }
                }
            )
        }

        composable(
            "call/{roomId}?incoming={incoming}&audioOnly={audioOnly}&peerName={peerName}",
            arguments = listOf(
                navArgument("roomId") { type = NavType.StringType },
                navArgument("incoming") { type = NavType.BoolType; defaultValue = false },
                navArgument("audioOnly") { type = NavType.BoolType; defaultValue = false },
                navArgument("peerName") { type = NavType.StringType; defaultValue = "Call" }
            )
        ) { backStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: return@composable
            val incoming = backStackEntry.arguments?.getBoolean("incoming") ?: false
            val audioOnly = backStackEntry.arguments?.getBoolean("audioOnly") ?: false
            val peerName = backStackEntry.arguments?.getString("peerName") ?: "Call"
            val callViewModel: CallViewModel = viewModel()
            val callState by callViewModel.uiState.collectAsState()

            LaunchedEffect(roomId, incoming, audioOnly) {
                callViewModel.joinCall(roomId, audioOnly = audioOnly, incoming = incoming, peerName = peerName)
            }

            CallScreen(
                uiState = callState,
                peerName = peerName,
                onToggleMute = { callViewModel.toggleMute() },
                onToggleVideo = { callViewModel.toggleVideo() },
                onToggleSpeaker = { callViewModel.toggleSpeaker() },
                onSwitchCamera = { callViewModel.switchCamera() },
                onAccept = { callViewModel.acceptIncoming() },
                onDecline = {
                    callViewModel.declineIncoming()
                    navController.popBackStack()
                },
                onShareResult = { resultCode, data ->
                    if (data != null) callViewModel.startScreenShare(resultCode, data)
                },
                onStopShare = { callViewModel.stopScreenShare() },
                onToggleScreenShare = { callViewModel.toggleScreenShare() },
                onEndCall = {
                    callViewModel.endCall()
                    navController.popBackStack()
                }
            )
        }
    } // NavHost

    // Spec 25: app lock sits above every screen until the device credential passes.
    if (appLockOn && !unlocked) {
        AppLockGate(
            onUnlock = {
                val km = activity?.getSystemService(android.app.KeyguardManager::class.java)
                val intent = runCatching {
                    km?.createConfirmDeviceCredentialIntent("Unlock Ollacore", "Confirm it is you")
                }.getOrNull()
                if (intent != null) {
                    runCatching { lockLauncher.launch(intent) }
                }
            }
        )
    }
    } // Box
}
