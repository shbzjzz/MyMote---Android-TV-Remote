package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.MyMote.tv.core.logging.TvLogger
import com.MyMote.tv.core.model.ConnectionState
import com.MyMote.tv.core.model.TvDevice
import com.MyMote.tv.core.util.NetworkMonitor
import com.MyMote.tv.discovery.NsdTvDiscoveryManager
import com.MyMote.tv.launcher.AppDefinition
import com.MyMote.tv.launcher.AppLauncher
import com.MyMote.tv.launcher.DefaultApps
import com.MyMote.tv.pairing.PairingState
import com.MyMote.tv.pairing.TvPairingManagerImpl
import com.MyMote.tv.remote.TvRemoteManagerImpl
import com.MyMote.tv.storage.TvCredentialStore
import com.MyMote.tv.ui.components.BottomNavBar
import com.MyMote.tv.ui.components.NavTab
import com.MyMote.tv.ui.components.PinEntryDialog
import com.MyMote.tv.ui.screens.AppsScreen
import com.MyMote.tv.ui.screens.DevicesScreen
import com.MyMote.tv.ui.screens.MediaScreen
import com.MyMote.tv.ui.screens.RemoteControlScreen
import com.MyMote.tv.ui.screens.SettingsScreen
import com.MyMote.tv.ui.theme.BackgroundLight
import com.MyMote.tv.ui.theme.MoteTvTheme

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.MyMote.tv.media.LocalMediaCastController
import com.MyMote.tv.auth.AuthManager
import com.MyMote.tv.ui.screens.SignInScreen

import com.MyMote.tv.mirror.ScreenMirrorController
import com.MyMote.tv.ui.screens.MirrorScreen
import com.MyMote.tv.adb.AdbConnectionManagerImpl
import com.MyMote.tv.adb.AdbConnectionState
import com.MyMote.tv.adb.AdbCredentialStore
import com.MyMote.tv.debloat.DebloatManager
import com.MyMote.tv.debloat.DebloatRepository
import com.MyMote.tv.debloat.TvPackageScanner
import com.MyMote.tv.debloat.TvRecoveryCoordinator
import com.MyMote.tv.tvhome.CleanYourTvLauncherManager
import com.MyMote.tv.tvhome.CleanYourTvThemeManager
import com.MyMote.tv.ui.screens.TvDebloatScreen
import com.MyMote.tv.ui.screens.TvHomeThemesScreen

enum class MainDisplayMode {
    TABS,
    DEVICES,
    TV_DEBLOAT,
    TV_HOME_THEMES
}

class MainActivity : ComponentActivity() {

    private lateinit var credentialStore: TvCredentialStore
    private lateinit var discoveryManager: NsdTvDiscoveryManager
    private lateinit var pairingManager: TvPairingManagerImpl
    private lateinit var remoteManager: TvRemoteManagerImpl
    private lateinit var castController: LocalMediaCastController
    private lateinit var mirrorController: ScreenMirrorController
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var authManager: AuthManager
    private lateinit var adbCredentialStore: AdbCredentialStore
    private lateinit var adbConnectionManager: AdbConnectionManagerImpl
    private lateinit var debloatRepository: DebloatRepository
    private lateinit var debloatManager: DebloatManager
    private lateinit var cleanYourTvLauncherManager: CleanYourTvLauncherManager
    private lateinit var cleanYourTvThemeManager: CleanYourTvThemeManager
    private lateinit var tvRecoveryCoordinator: TvRecoveryCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TvLogger.i(TvLogger.TAG_GENERAL, "MyMote Android TV Remote initialized (v2.5 Cyber Glass Edition)")

        credentialStore = TvCredentialStore(applicationContext)
        discoveryManager = NsdTvDiscoveryManager(applicationContext)
        pairingManager = TvPairingManagerImpl(credentialStore)
        remoteManager = TvRemoteManagerImpl(credentialStore)
        castController = LocalMediaCastController(applicationContext, remoteManager)
        mirrorController = ScreenMirrorController(applicationContext, remoteManager)
        networkMonitor = NetworkMonitor(applicationContext)
        authManager = AuthManager(applicationContext)

        adbCredentialStore = AdbCredentialStore(applicationContext)
        adbConnectionManager = AdbConnectionManagerImpl(applicationContext, adbCredentialStore)
        debloatRepository = DebloatRepository(applicationContext)
        val scanner = TvPackageScanner(adbConnectionManager, debloatRepository)
        debloatManager = DebloatManager(adbConnectionManager, scanner, debloatRepository)
        cleanYourTvLauncherManager = CleanYourTvLauncherManager(applicationContext, adbConnectionManager)
        cleanYourTvThemeManager = CleanYourTvThemeManager(adbConnectionManager)
        tvRecoveryCoordinator = TvRecoveryCoordinator(debloatManager, cleanYourTvLauncherManager)

        networkMonitor.startMonitoring(
            onNetworkAvailable = { isWifi ->
                TvLogger.i(TvLogger.TAG_DISCOVERY, "Network available (isWifi=$isWifi), discovering and restoring connection if needed")
                discoveryManager.stopDiscovery()
                discoveryManager.startDiscovery()
                remoteManager.onNetworkRestored()
            },
            onNetworkLost = {
                TvLogger.w(TvLogger.TAG_DISCOVERY, "Active network lost, invalidating remote connection")
                remoteManager.onNetworkLost()
            }
        )

        // Auto-start discovery on launch
        discoveryManager.startDiscovery()

        setContent {
            MoteTvTheme {
                val currentUser by authManager.currentUser.collectAsStateWithLifecycle()
                val isGuestMode by authManager.isGuestMode.collectAsStateWithLifecycle()
                if (currentUser == null && !isGuestMode) {
                    SignInScreen(authManager = authManager)
                } else {
                    MainAppHost(
                        credentialStore = credentialStore,
                        discoveryManager = discoveryManager,
                        pairingManager = pairingManager,
                        remoteManager = remoteManager,
                        castController = castController,
                        mirrorController = mirrorController,
                        authManager = authManager,
                        adbConnectionManager = adbConnectionManager,
                        debloatManager = debloatManager,
                        cleanYourTvLauncherManager = cleanYourTvLauncherManager,
                        cleanYourTvThemeManager = cleanYourTvThemeManager,
                        tvRecoveryCoordinator = tvRecoveryCoordinator
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Seamlessly reconnect remote only if connection dropped while app was backgrounded or screen was off
        remoteManager.onAppResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        discoveryManager.stopDiscovery()
        remoteManager.disconnect()
        adbConnectionManager.stopWirelessDiscovery()
        adbConnectionManager.disconnect()
        // Do NOT call castController.stopCast() here!
        // Casting is owned by MediaStreamService and must continue playing while screen is off,
        // device is locked, or activity is recreated.
        castController.cleanUpOnAppExit()
        pairingManager.cancelPairing()
        networkMonitor.stopMonitoring()
    }
}

@Composable
fun MainAppHost(
    credentialStore: TvCredentialStore,
    discoveryManager: NsdTvDiscoveryManager,
    pairingManager: TvPairingManagerImpl,
    remoteManager: TvRemoteManagerImpl,
    castController: LocalMediaCastController,
    mirrorController: ScreenMirrorController,
    authManager: AuthManager,
    adbConnectionManager: AdbConnectionManagerImpl,
    debloatManager: DebloatManager,
    cleanYourTvLauncherManager: CleanYourTvLauncherManager,
    cleanYourTvThemeManager: CleanYourTvThemeManager,
    tvRecoveryCoordinator: TvRecoveryCoordinator
) {
    var displayMode by remember { mutableStateOf(MainDisplayMode.TABS) }
    var selectedTab by remember { mutableStateOf(NavTab.REMOTE) }
    var targetDevice by remember { mutableStateOf<TvDevice?>(null) }

    // App definitions state: defaults + custom apps
    var customApps by remember { mutableStateOf(credentialStore.getCustomApps()) }
    var favoriteAppIds by remember { mutableStateOf(credentialStore.getFavoriteAppIds()) }
    var savedDevices by remember { mutableStateOf(credentialStore.getSavedDevices()) }

    val allApps = remember(customApps) {
        DefaultApps.ALL_DEFAULT_APPS + customApps
    }

    val favoriteApps = remember(allApps, favoriteAppIds) {
        allApps.filter { favoriteAppIds.contains(it.id) }
    }

    val discoveredDevices by discoveryManager.discoveredDevices.collectAsStateWithLifecycle()
    val isDiscovering by discoveryManager.isDiscovering.collectAsStateWithLifecycle()
    val pairingState by pairingManager.pairingState.collectAsStateWithLifecycle()
    val connectionState by remoteManager.connectionState.collectAsStateWithLifecycle()
    val castSessionState by castController.sessionState.collectAsStateWithLifecycle()
    val imeActive by remoteManager.imeActive.collectAsStateWithLifecycle()
    val lastImeText by remoteManager.lastImeText.collectAsStateWithLifecycle()
    val adbState by adbConnectionManager.connectionState.collectAsStateWithLifecycle()
    val tvHomeLauncherStatus by cleanYourTvLauncherManager.launcherStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            castController.onMediaFilePicked(uri)
        }
    }

    // When pairing succeeds, automatically save and connect to remote port 6466
    LaunchedEffect(pairingState) {
        if (pairingState is PairingState.Paired) {
            val pairedDev = (pairingState as PairingState.Paired).device
            targetDevice = pairedDev
            credentialStore.saveDevice(pairedDev)
            savedDevices = credentialStore.getSavedDevices()
            remoteManager.connect(pairedDev)
            displayMode = MainDisplayMode.TABS
        }
    }

    // Auto-select first discovered or saved TV if none selected
    LaunchedEffect(discoveredDevices, savedDevices) {
        if (targetDevice == null) {
            val saved = savedDevices.firstOrNull()
            if (saved != null) {
                targetDevice = saved
                if (credentialStore.isDevicePaired(saved.id)) {
                    remoteManager.connect(saved)
                }
            } else if (discoveredDevices.isNotEmpty()) {
                val first = discoveredDevices.first()
                targetDevice = first
                if (credentialStore.isDevicePaired(first.id)) {
                    remoteManager.connect(first)
                }
            }
        }
    }

    val onConnectToDevice: (TvDevice) -> Unit = { dev ->
        targetDevice = dev
        credentialStore.saveDevice(dev)
        savedDevices = credentialStore.getSavedDevices()
        if (credentialStore.isDevicePaired(dev.id)) {
            remoteManager.connect(dev)
            displayMode = MainDisplayMode.TABS
        } else {
            pairingManager.startPairing(dev)
        }
    }

    when (displayMode) {
        MainDisplayMode.TABS -> {
            Scaffold(
                containerColor = BackgroundLight,
                bottomBar = {
                    BottomNavBar(
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it }
                    )
                }
            ) { scaffoldPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(scaffoldPadding)
                ) {
                    when (selectedTab) {
                        NavTab.REMOTE -> {
                            RemoteControlScreen(
                                device = targetDevice,
                                connectionState = connectionState,
                                onSendKey = { keyCode, dir -> remoteManager.sendKey(keyCode, dir) },
                                onSendImeText = { text -> remoteManager.sendImeText(text) },
                                onSendImeTextWithFallback = { text, fallback -> remoteManager.sendImeTextWithFallback(text, fallback) },
                                onSendChar = { char -> remoteManager.sendChar(char) },
                                imeActive = imeActive,
                                lastImeText = lastImeText,
                                onOpenDevices = {
                                    displayMode = MainDisplayMode.DEVICES
                                    discoveryManager.startDiscovery()
                                },
                                onReconnect = {
                                    targetDevice?.let { dev ->
                                        onConnectToDevice(dev)
                                    }
                                },
                                onDisconnect = {
                                    remoteManager.disconnect()
                                },
                                favoriteApps = favoriteApps,
                                onLaunchApp = { app ->
                                    AppLauncher.launch(app, remoteManager)
                                },
                                onLaunchUrl = { url ->
                                    remoteManager.launchAppLink(url)
                                },
                                onPickMediaClick = {
                                    mediaPickerLauncher.launch(arrayOf("video/*", "audio/*", "*/*"))
                                },
                                onOpenAllApps = {
                                    selectedTab = NavTab.APPS
                                },
                                hapticEnabled = credentialStore.isHapticEnabled()
                            )
                        }

                        NavTab.MIRROR -> {
                            MirrorScreen(
                                mirrorController = mirrorController,
                                targetDevice = targetDevice,
                                connectionState = connectionState
                            )
                        }

                        NavTab.APPS -> {
                            AppsScreen(
                                device = targetDevice,
                                connectionState = connectionState,
                                remoteManager = remoteManager,
                                allApps = allApps,
                                favoriteAppIds = favoriteAppIds,
                                onToggleFavorite = { appId ->
                                    credentialStore.toggleFavoriteApp(appId)
                                    favoriteAppIds = credentialStore.getFavoriteAppIds()
                                },
                                onAddCustomApp = { newApp ->
                                    credentialStore.saveCustomApp(newApp)
                                    customApps = credentialStore.getCustomApps()
                                    favoriteAppIds = credentialStore.getFavoriteAppIds()
                                },
                                onRemoveCustomApp = { appId ->
                                    credentialStore.removeCustomApp(appId)
                                    customApps = credentialStore.getCustomApps()
                                    favoriteAppIds = credentialStore.getFavoriteAppIds()
                                },
                                onOpenDevices = {
                                    displayMode = MainDisplayMode.DEVICES
                                    discoveryManager.startDiscovery()
                                },
                                onReconnect = {
                                    targetDevice?.let { dev ->
                                        onConnectToDevice(dev)
                                    }
                                }
                            )
                        }

                        NavTab.MEDIA -> {
                            MediaScreen(
                                device = targetDevice,
                                connectionState = connectionState,
                                onSendKey = { keyCode, dir -> remoteManager.sendKey(keyCode, dir) },
                                onOpenDevices = {
                                    displayMode = MainDisplayMode.DEVICES
                                    discoveryManager.startDiscovery()
                                },
                                onReconnect = {
                                    targetDevice?.let { dev ->
                                        onConnectToDevice(dev)
                                    }
                                },
                                castController = castController,
                                sessionState = castSessionState,
                                onPickMediaClick = {
                                    mediaPickerLauncher.launch(arrayOf("video/*", "audio/*", "*/*"))
                                }
                            )
                        }

                        NavTab.SETTINGS -> {
                            val modifiedPkgs by debloatManager.modifiedPackages.collectAsStateWithLifecycle()
                            val latestBackup by debloatManager.latestBackup.collectAsStateWithLifecycle()
                            val persistedOrigHome = tvHomeLauncherStatus.originalHomePackage
                                ?: latestBackup?.originalHomePackage
                                ?: tvRecoveryCoordinator.resolvePersistedOriginalHomePackage()
                            SettingsScreen(
                                currentDevice = targetDevice,
                                connectionState = connectionState,
                                savedDevices = savedDevices,
                                credentialStore = credentialStore,
                                onSelectDevice = { dev ->
                                    onConnectToDevice(dev)
                                },
                                onOpenDiscovery = {
                                    displayMode = MainDisplayMode.DEVICES
                                    discoveryManager.startDiscovery()
                                },
                                onAddManualDevice = { name, ip ->
                                    val added = discoveryManager.addManualDevice(name, ip)
                                    onConnectToDevice(added)
                                },
                                onRemoveDevice = { devId ->
                                    credentialStore.removeDevice(devId)
                                    savedDevices = credentialStore.getSavedDevices()
                                    if (targetDevice?.id == devId) {
                                        remoteManager.disconnect()
                                        targetDevice = savedDevices.firstOrNull()
                                    }
                                },
                                onRenameDevice = { devId, newName ->
                                    credentialStore.renameDevice(devId, newName)
                                    savedDevices = credentialStore.getSavedDevices()
                                    if (targetDevice?.id == devId) {
                                        targetDevice = targetDevice?.copy(name = newName)
                                    }
                                },
                                authManager = authManager,
                                onOpenTvDebloat = {
                                    displayMode = MainDisplayMode.TV_DEBLOAT
                                },
                                onOpenTvHomeThemes = {
                                    displayMode = MainDisplayMode.TV_HOME_THEMES
                                },
                                adbConnectedHost = (adbState as? AdbConnectionState.Connected)?.let { "${it.host}:${it.port}" },
                                modifiedPackagesCount = modifiedPkgs.size,
                                originalHomePackage = persistedOrigHome,
                                onRunTvRecovery = {
                                    tvRecoveryCoordinator.recoverTv().map { it.formatSummary() }
                                }
                            )
                        }
                    }
                }
            }
        }

        MainDisplayMode.DEVICES -> {
            BackHandler {
                displayMode = MainDisplayMode.TABS
            }
            DevicesScreen(
                discoveredDevices = discoveredDevices,
                isDiscovering = isDiscovering,
                onStartScan = { discoveryManager.startDiscovery() },
                onStopScan = { discoveryManager.stopDiscovery() },
                onAddManualDevice = { name, ip ->
                    val added = discoveryManager.addManualDevice(name, ip)
                    displayMode = MainDisplayMode.TABS
                    onConnectToDevice(added)
                },
                onSelectDevice = { dev ->
                    displayMode = MainDisplayMode.TABS
                    onConnectToDevice(dev)
                },
                onBack = { displayMode = MainDisplayMode.TABS }
            )
        }

        MainDisplayMode.TV_DEBLOAT -> {
            TvDebloatScreen(
                adbManager = adbConnectionManager,
                debloatManager = debloatManager,
                currentRemoteDevice = targetDevice,
                isCleanYourTvActiveHome = tvHomeLauncherStatus.isCleanYourTvActiveHome,
                isCleanYourTvInstalled = tvHomeLauncherStatus.isCleanYourTvInstalled,
                onBack = { displayMode = MainDisplayMode.TABS }
            )
        }

        MainDisplayMode.TV_HOME_THEMES -> {
            TvHomeThemesScreen(
                adbManager = adbConnectionManager,
                launcherManager = cleanYourTvLauncherManager,
                themeManager = cleanYourTvThemeManager,
                currentRemoteDevice = targetDevice,
                onBack = { displayMode = MainDisplayMode.TABS }
            )
        }
    }

    // Handle PIN entry dialog overlay during pairing
    when (val state = pairingState) {
        is PairingState.WaitingForUserPin -> {
            PinEntryDialog(
                tvName = state.tvName,
                prompt = state.prompt,
                errorMessage = state.errorMessage,
                isSubmitting = state.isSubmitting,
                onPinSubmit = { pin ->
                    pairingManager.submitPin(pin)
                },
                onDismiss = {
                    pairingManager.cancelPairing()
                }
            )
        }
        else -> Unit
    }
}
