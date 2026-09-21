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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ollacore.app.auth.AuthViewModel
import com.ollacore.app.auth.AuthViewModel.AuthStep
import com.ollacore.app.ui.call.CallScreen
import com.ollacore.app.ui.call.CallViewModel
import com.ollacore.app.ui.calls.CallHistoryViewModel
import com.ollacore.app.ui.chat.ChatScreen
import com.ollacore.app.ui.chat.ChatViewModel
import com.ollacore.app.ui.contacts.ContactsScreen
import com.ollacore.app.ui.contacts.ContactsViewModel
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
                initial = com.ollacore.app.data.local.ThemeMode.BLUE
            )
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

@Composable
fun OllacoreNavHost() {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = viewModel()
    val authState by authViewModel.uiState.collectAsState()

    // Splash shows first (brand moment); step routing resumes after it completes.
    var splashDone by remember { mutableStateOf(false) }
    val onboardingDone by authViewModel.onboardingDone.collectAsState()

    LaunchedEffect(authState.step, splashDone) {
        if (!splashDone) return@LaunchedEffect
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
            SplashScreen(
                onTimeout = {
                    splashDone = true
                    // Fresh installs see onboarding once; everyone else follows the auth step.
                    if (onboardingDone == false) {
                        navController.navigate("onboarding") {
                            popUpTo("splash") { inclusive = true }
                        }
                    } else {
                        when (authState.step) {
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

            HomeScreen(
                uiState = homeState,
                onConversationClick = { roomId ->
                    navController.navigate("chat/$roomId")
                },
                onNewChat = { navController.navigate("contacts") },
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
                onNewGroup = { navController.navigate("newGroup") }
            )
        }

        composable(
            "chat/{roomId}",
            arguments = listOf(navArgument("roomId") { type = NavType.StringType })
        ) { backStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: return@composable
            val chatViewModel: ChatViewModel = viewModel()
            val chatState by chatViewModel.uiState.collectAsState()

            LaunchedEffect(roomId) {
                chatViewModel.joinRoom(roomId)
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
                onProfileClick = {
                    if (chatState.kind.equals("group", ignoreCase = true)) navController.navigate("groupInfo/$roomId")
                    else navController.navigate("profile")
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
                onSendLocation = {
                    // Demo location (backend/API check required for kind=location).
                    // Replace with FusedLocationProviderClient lastLocation in production.
                    chatViewModel.sendLocation(12.9716, 77.5946, "📍 Shared location")
                },
                onResolveUrl = { aid -> chatViewModel.resolveAttachmentUrl(aid) },
                onClearUploadError = { chatViewModel.clearUploadError() },
                onRetryMessage = { msg -> chatViewModel.retryForMessage(msg) }
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
            val scope = rememberCoroutineScope()

            ContactsScreen(
                contacts = contactsState.contacts,
                isLoading = contactsState.isLoading,
                onBack = { navController.popBackStack() },
                onContactClick = { userId ->
                    scope.launch {
                        contactsViewModel.openDirect(userId)?.let { targetRoomId ->
                            navController.navigate("chat/$targetRoomId")
                        }
                    }
                },
                onRefresh = { contactsViewModel.refresh() },
                onNewGroup = { navController.navigate("newGroup") },
                onAddByPhone = { phone ->
                    scope.launch {
                        contactsViewModel.lookupUserId(phone)?.let { userId ->
                            contactsViewModel.openDirect(userId)?.let { targetRoomId ->
                                navController.navigate("chat/$targetRoomId")
                            }
                        }
                    }
                }
            )
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
