package com.MyMote.tv.tvhome

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.MyMote.tv.adb.AdbCommandResult
import com.MyMote.tv.adb.AdbConnectionManager
import com.MyMote.tv.adb.AdbConnectionMode
import com.MyMote.tv.adb.AdbConnectionState
import com.MyMote.tv.adb.AdbDiagnosticCategory
import com.MyMote.tv.adb.AdbEndpointConfig
import com.MyMote.tv.adb.AdbPairingCapability
import com.MyMote.tv.adb.AdbPairingState
import com.MyMote.tv.adb.DiscoveredAdbService
import com.MyMote.tv.adb.TvDeviceInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CleanYourTvLauncherAndThemeTest {

    private lateinit var context: Context

    @Before
    fun clearLauncherPrefs() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("motetv_tvhome_recovery", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun launcherLifecycleInstallsSwitchesVerifiesAndRestoresOriginalHome() = runTest {
        val adb = FakeHomeAdbManager(sdkVersion = 28)
        val launcherManager = CleanYourTvLauncherManager(context = context, adbManager = adb)

        val initial = launcherManager.refreshLauncherStatus().getOrThrow()
        assertTrue(initial.isSdkCompatible)
        assertFalse(initial.isCleanYourTvInstalled)
        assertEquals("com.google.android.tvlauncher", initial.currentHomePackage)
        assertEquals("com.google.android.tvlauncher", initial.originalHomePackage)

        // Cannot activate before APK installed
        assertTrue(launcherManager.activateCleanYourTvAsHome().isFailure)

        // Install CleanYourTV Home APK -> verifies package appears without touching stock launcher
        val installed = launcherManager.installCleanYourTvApk(byteArrayOf(0x50, 0x4B, 0x03, 0x04)).getOrThrow()
        assertTrue(installed.isCleanYourTvInstalled)
        assertTrue(adb.installedPackages.contains("com.google.android.tvlauncher"))

        // Switch default HOME to CleanYourTV Home & verify active HOME resolved to com.gathin.tvhome
        val switched = launcherManager.activateCleanYourTvAsHome().getOrThrow()
        assertTrue(switched.isCleanYourTvActiveHome)
        assertEquals(CleanYourTvConstants.LAUNCHER_PACKAGE, switched.currentHomePackage)
        assertEquals("com.google.android.tvlauncher", switched.originalHomePackage)

        // Restore original stock HOME & verify
        val restored = launcherManager.restoreOriginalHomeLauncher().getOrThrow()
        assertFalse(restored.isCleanYourTvActiveHome)
        assertEquals("com.google.android.tvlauncher", restored.currentHomePackage)
        assertTrue(adb.enabledPackages.contains("com.google.android.tvlauncher"))
    }

    @Test
    fun handlesFirmwareRefusalToSetHomeSafelyWithoutDisablingOriginalLauncher() = runTest {
        val adb = FakeHomeAdbManager(sdkVersion = 29, failSetHomeActivity = true)
        adb.installedPackages.add(CleanYourTvConstants.LAUNCHER_PACKAGE)
        val launcherManager = CleanYourTvLauncherManager(context = context, adbManager = adb)

        val activateRes = launcherManager.activateCleanYourTvAsHome()
        assertTrue(activateRes.isFailure)
        assertEquals("com.google.android.tvlauncher", launcherManager.launcherStatus.value.currentHomePackage)
        assertTrue(launcherManager.launcherStatus.value.statusDetail.contains("Original HOME kept safely active"))
    }

    @Test
    fun restoreOriginalHomeChecksEnablePackageResultVerifiesEnabledAndPreservesRecordUntilVerified() = runTest {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("motetv_tvhome_recovery", android.content.Context.MODE_PRIVATE).edit().clear().commit()

        val adb = FakeHomeAdbManager(sdkVersion = 28)
        adb.installedPackages.add(CleanYourTvConstants.LAUNCHER_PACKAGE)
        val launcherManager = CleanYourTvLauncherManager(context = context, adbManager = adb)

        // Capture stock HOME & switch to CleanYourTV
        launcherManager.refreshLauncherStatus().getOrThrow()
        launcherManager.activateCleanYourTvAsHome().getOrThrow()
        assertEquals(CleanYourTvConstants.LAUNCHER_PACKAGE, adb.currentHomePackage)

        // Case 1: enablePackage returns failure -> aborts with exact error, does NOT call setHome, preserves original HOME record
        adb.disabledPackages.add("com.google.android.tvlauncher")
        adb.failEnableWith = IOException("SecurityException: package manager locked")
        val failEnableRes = launcherManager.restoreOriginalHomeLauncher()
        assertTrue(failEnableRes.isFailure)
        val errMsg = failEnableRes.exceptionOrNull()?.message.orEmpty()
        assertTrue("Exact enable error must be reported: $errMsg", errMsg.contains("package manager locked"))
        assertEquals(1, adb.setHomeCalls.size) // only the initial activate call, zero restore HOME calls
        assertEquals("com.google.android.tvlauncher", launcherManager.getPersistedOriginalHome().first)

        // Case 2: enablePackage returns non-zero exit code -> aborts and preserves record
        adb.failEnableWith = null
        adb.failEnableResult = AdbCommandResult(1, "", "Permission denied enabling stock launcher")
        val failExitRes = launcherManager.restoreOriginalHomeLauncher()
        assertTrue(failExitRes.isFailure)
        assertTrue(failExitRes.exceptionOrNull()?.message.orEmpty().contains("Permission denied"))
        assertEquals(1, adb.setHomeCalls.size)
        assertEquals("com.google.android.tvlauncher", launcherManager.getPersistedOriginalHome().first)

        // Case 3: enablePackage reports exit 0, but launcher still appears in disabled packages -> aborts before HOME switch
        adb.failEnableResult = null
        adb.ignoreEnable = true
        val stillDisabledRes = launcherManager.restoreOriginalHomeLauncher()
        assertTrue(stillDisabledRes.isFailure)
        assertTrue(stillDisabledRes.exceptionOrNull()?.message.orEmpty().contains("remains disabled"))
        assertEquals(1, adb.setHomeCalls.size)
        assertEquals("com.google.android.tvlauncher", launcherManager.getPersistedOriginalHome().first)

        // Case 4: Real enable succeeds and final HOME verification confirms stock HOME active
        adb.ignoreEnable = false
        val okRes = launcherManager.restoreOriginalHomeLauncher().getOrThrow()
        assertEquals("com.google.android.tvlauncher", okRes.currentHomePackage)
        assertEquals(2, adb.setHomeCalls.size)
        assertEquals("com.google.android.tvlauncher", launcherManager.getPersistedOriginalHome().first)
    }

    @Test
    fun failedOriginalHomePersistenceAbortsHomeSwitchBeforeCallingSetHome() = runTest {
        val adb = FakeHomeAdbManager(sdkVersion = 28)
        adb.installedPackages.add(CleanYourTvConstants.LAUNCHER_PACKAGE)
        val launcherManager = CleanYourTvLauncherManager(
            context = null,
            adbManager = adb,
            simulatePersistenceFailureForTest = true
        )

        val switchRes = launcherManager.activateCleanYourTvAsHome()
        assertTrue("HOME switch must abort if original HOME cannot be persisted", switchRes.isFailure)
        assertTrue("setDefaultHomeActivity must NOT be called if original HOME persistence failed", adb.setHomeCalls.isEmpty())
        assertEquals("com.google.android.tvlauncher", adb.currentHomePackage)
        assertTrue("Original launcher must remain enabled", adb.enabledPackages.contains("com.google.android.tvlauncher"))
    }

    @Test
    fun restoreFailsVerificationIfTvResolvesDifferentActiveHomeAndKeepsRecord() = runTest {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("motetv_tvhome_recovery", android.content.Context.MODE_PRIVATE).edit().clear().commit()

        val adb = FakeHomeAdbManager(sdkVersion = 28)
        adb.installedPackages.add(CleanYourTvConstants.LAUNCHER_PACKAGE)
        val launcherManager = CleanYourTvLauncherManager(context = context, adbManager = adb)

        launcherManager.refreshLauncherStatus().getOrThrow()
        launcherManager.activateCleanYourTvAsHome().getOrThrow()

        // Firmware ignores restore HOME change and keeps CleanYourTV active
        adb.ignoreSetHomeRestore = true
        val restoreRes = launcherManager.restoreOriginalHomeLauncher()
        assertTrue("Restore must fail when active HOME does not resolve to original HOME", restoreRes.isFailure)
        assertEquals("com.google.android.tvlauncher", launcherManager.getPersistedOriginalHome().first)
    }

    @Test
    fun nullContextOrUnavailableStorageAbortsHomeSwitchWithoutMemoryFallback() = runTest {
        val adb = FakeHomeAdbManager(sdkVersion = 28)
        adb.installedPackages.add(CleanYourTvConstants.LAUNCHER_PACKAGE)
        val launcherManager = CleanYourTvLauncherManager(context = null, adbManager = adb)

        val switchRes = launcherManager.activateCleanYourTvAsHome()
        assertTrue("HOME switch must abort when context/SharedPreferences is unavailable", switchRes.isFailure)
        assertTrue("setDefaultHomeActivity must NOT run without durable backup", adb.setHomeCalls.isEmpty())
        assertEquals(null to null, launcherManager.getPersistedOriginalHome())
        assertEquals("com.google.android.tvlauncher", adb.currentHomePackage)
    }

    @Test
    fun unmarkedOrPartialPersistedBackupFailsClosedWithoutGuessing() = runTest {
        val adb = FakeHomeAdbManager(sdkVersion = 28)
        adb.installedPackages.add(CleanYourTvConstants.LAUNCHER_PACKAGE)
        adb.currentHomePackage = CleanYourTvConstants.LAUNCHER_PACKAGE
        adb.currentHomeActivity = CleanYourTvConstants.LAUNCHER_COMPONENT

        // Case 1: Prefs has package & activity, but lacks orig_home_valid=true
        val prefs = context.getSharedPreferences("motetv_tvhome_recovery", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("orig_home_pkg", "com.google.android.tvlauncher")
            .putString("orig_home_act", "com.google.android.tvlauncher/.MainActivity")
            .remove("orig_home_valid")
            .commit()

        val mgrUnmarked = CleanYourTvLauncherManager(context = context, adbManager = adb)
        assertEquals(null to null, mgrUnmarked.getPersistedOriginalHome())
        val resUnmarked = mgrUnmarked.restoreOriginalHomeLauncher()
        assertTrue("Must refuse restore when backup is not marked valid", resUnmarked.isFailure)
        assertTrue("Must never call setDefaultHomeActivity on unverified backup", adb.setHomeCalls.isEmpty())

        // Case 2: Marked valid=true, but component is missing '/' (corrupted/incomplete)
        prefs.edit()
            .putBoolean("orig_home_valid", true)
            .putString("orig_home_act", "com.google.android.tvlauncher")
            .commit()
        val mgrPartial = CleanYourTvLauncherManager(context = context, adbManager = adb)
        assertEquals(null to null, mgrPartial.getPersistedOriginalHome())
        val resPartial = mgrPartial.restoreOriginalHomeLauncher()
        assertTrue("Must refuse restore when activity component is malformed", resPartial.isFailure)
        assertTrue(adb.setHomeCalls.isEmpty())
    }

    @Test
    fun activateAndRestoreDiscoverNonMainActivityHomeComponentWithoutGuessing() = runTest {
        val customPkg = "com.custom.tvlauncher"
        val customAct = "$customPkg/.TvHomeActivity"
        val adb = FakeHomeAdbManager(sdkVersion = 29).apply {
            installedPackages.clear()
            installedPackages.addAll(listOf(customPkg, CleanYourTvConstants.LAUNCHER_PACKAGE, "com.android.settings"))
            enabledPackages.clear()
            enabledPackages.add(customPkg)
            registeredHomeComponents[customPkg] = customAct
            currentHomePackage = customPkg
            currentHomeActivity = customAct
        }

        val launcherManager = CleanYourTvLauncherManager(context = context, adbManager = adb)
        val activated = launcherManager.activateCleanYourTvAsHome().getOrThrow()
        assertEquals(customPkg, activated.originalHomePackage)
        assertEquals(customAct, activated.originalHomeActivity)
        assertEquals(customPkg to customAct, launcherManager.getPersistedOriginalHome())

        val restored = launcherManager.restoreOriginalHomeLauncher().getOrThrow()
        assertEquals(customPkg, restored.currentHomePackage)
        assertEquals(customAct, adb.setHomeCalls.last())
        assertFalse(
            "Must never guess /.MainActivity when restoring custom launcher",
            adb.setHomeCalls.any { it == "$customPkg/.MainActivity" }
        )
    }

    @Test
    fun restoreAbortsWithoutGuessingWhenNoDiscoverableHomeActivityAndPreservesBackup() = runTest {
        val adb = FakeHomeAdbManager(sdkVersion = 28)
        adb.installedPackages.add(CleanYourTvConstants.LAUNCHER_PACKAGE)
        val launcherManager = CleanYourTvLauncherManager(context = context, adbManager = adb)

        launcherManager.activateCleanYourTvAsHome().getOrThrow()
        assertEquals(1, adb.setHomeCalls.size)

        // Package exists & is enabled, but exposes zero HOME activities and cannot resolve one
        adb.homeActivitiesOverride = emptyList()
        adb.resolvedHomeActivityOverride = null

        val res = launcherManager.restoreOriginalHomeLauncher()
        assertTrue("Restore must abort without guessing when no HOME activity can be discovered", res.isFailure)
        val msg = res.exceptionOrNull()?.message.orEmpty()
        assertTrue("Failure must provide manual TV Settings instructions: $msg", msg.contains("Manual step on TV"))
        assertEquals("setDefaultHomeActivity must NOT be called with a guessed component", 1, adb.setHomeCalls.size)
        assertEquals("com.google.android.tvlauncher", launcherManager.getPersistedOriginalHome().first)
    }

    @Test
    fun activateAbortsWhenTargetLauncherExposesNoValidHomeActivity() = runTest {
        val adb = FakeHomeAdbManager(sdkVersion = 28).apply {
            installedPackages.add(CleanYourTvConstants.LAUNCHER_PACKAGE)
            homeActivitiesOverride = listOf("com.google.android.tvlauncher/.MainActivity")
        }
        val launcherManager = CleanYourTvLauncherManager(context = context, adbManager = adb)

        val res = launcherManager.activateCleanYourTvAsHome()
        assertTrue("Activation must abort if CleanYourTV Home exposes no valid HOME activity", res.isFailure)
        assertTrue(res.exceptionOrNull()?.message.orEmpty().contains("does not expose a valid HOME activity"))
        assertTrue("setDefaultHomeActivity must NOT be called", adb.setHomeCalls.isEmpty())
    }

    @Test
    fun firmwareBlockedSwitchReturnsManualTvSettingsInstructions() = runTest {
        val adb = FakeHomeAdbManager(sdkVersion = 29).apply {
            installedPackages.add(CleanYourTvConstants.LAUNCHER_PACKAGE)
            setHomeResultOverride = AdbCommandResult(1, "", "SecurityException: blocked by MIUI")
        }
        val launcherManager = CleanYourTvLauncherManager(context = context, adbManager = adb)

        val res = launcherManager.activateCleanYourTvAsHome()
        assertTrue(res.isFailure)
        val msg = res.exceptionOrNull()?.message.orEmpty()
        assertTrue("Must guide user to TV Settings > Apps > Default apps: $msg", msg.contains("TV Settings"))
        assertTrue(msg.contains("Home app"))
    }

    @Test
    fun themeCatalogMatchesRealCleanYourTvSchemaWithoutUnsupportedKeys() = runTest {
        assertTrue(CleanYourTvThemeCatalog.PRESETS.size >= 29)

        val config = CleanYourTvThemeConfig(
            selectedPresetId = "dock-glass",
            backgroundColorHex = "#0a0f1a",
            accentColorHex = "INVALID",
            columns = 6,
            dockRow = true,
            singleRow = false,
            dockBandAll = true,
            dockGlass = true,
            dockOpacity = 65,
            ambientGlow = true,
            glowSnap = false,
            skyIntensity = 80,
            clockFormat = "12h", // normalizes to "12"
            showStats = true,
            reducedMotion = true,
            greetingName = "Living Room",
            wallpaperEnabled = true,
            wallpaperRotationMinutes = 30,
            wallpaperOrder = "random",
            wallpaperDim = 35,
            wallpaperCaptions = true,
            dockApps = listOf("com.netflix.ninja"),
            hiddenApps = listOf("com.example.bloat")
        )

        val jsonText = CleanYourTvThemeCatalog.buildConfigJson(config)
        val parsed = JSONObject(jsonText)

        // Every emitted key MUST belong to real CleanYourTV supported schema
        val keys = mutableSetOf<String>()
        parsed.keys().forEach { keys.add(it) }
        assertEquals(CleanYourTvThemeCatalog.SUPPORTED_CONFIG_KEYS, keys)

        // Hallucinated / legacy wrong keys MUST NOT exist
        assertFalse(parsed.has("theme_id"))
        assertFalse(parsed.has("dock_slots"))
        assertFalse(parsed.has("clock_style"))
        assertFalse(parsed.has("dock_blur"))

        // Critical field types & CleanYourTV values
        assertTrue("wallpaper MUST be Boolean", parsed.get("wallpaper") is Boolean)
        assertEquals(true, parsed.getBoolean("wallpaper"))
        assertEquals("#0A0F1A", parsed.getString("background_color"))
        assertEquals("#38BDF8", parsed.getString("accent_color"))
        assertEquals(6, parsed.getInt("columns"))
        assertEquals(true, parsed.getBoolean("dock_glass"))
        assertEquals(65, parsed.getInt("dock_opacity"))
        assertEquals("12", parsed.getString("clock_format"))
        assertEquals(30, parsed.getInt("wallpaper_rotation_minutes"))
        assertEquals("random", parsed.getString("wallpaper_order"))
        assertEquals(35, parsed.getInt("wallpaper_dim"))
        assertEquals(true, parsed.getBoolean("wallpaper_captions"))
        assertEquals(80, parsed.getInt("sky_intensity"))
        assertEquals("Living Room", parsed.getString("greeting_name"))

        assertTrue(CleanYourTvThemeCatalog.validateDeployedConfig(jsonText, config).isSuccess)
    }

    @Test
    fun applyThemeDeploysWallpaperVerifiesReadBackRestartsLauncherAndSupportsRollbackReset() = runTest {
        val adb = FakeHomeAdbManager(sdkVersion = 28)
        adb.installedPackages.add(CleanYourTvConstants.LAUNCHER_PACKAGE)
        val themeManager = CleanYourTvThemeManager(adb)

        val aurora = CleanYourTvThemeCatalog.PRESETS.first { it.id == "aurora" }
        themeManager.selectPreset(aurora)
        themeManager.updateConfig {
            it.copy(
                columns = 6,
                dockGlass = true,
                dockOpacity = 70,
                clockFormat = "12",
                showStats = true,
                reducedMotion = true
            )
        }

        // Single static wallpaper pushes directly to /files/wallpaper.jpg and verifies boolean wallpaper=true
        val jsonText = themeManager.applyThemeToTv(
            wallpaperBytes = byteArrayOf(1, 2, 3, 4),
            wallpaperName = "aurora.jpg"
        ).getOrThrow()

        val parsed = JSONObject(jsonText)
        assertEquals(true, parsed.getBoolean("wallpaper"))
        assertEquals("#34D399", parsed.getString("accent_color"))
        assertTrue(adb.pushedFiles.containsKey(CleanYourTvConstants.REMOTE_WALLPAPER_PATH))
        assertTrue(adb.pushedFiles.containsKey(CleanYourTvConstants.REMOTE_CONFIG_PATH))
        assertEquals(1, adb.restartCalls)

        // Multi-wallpaper with rotation deploys into /files/wallpapers/
        themeManager.updateConfig { it.copy(wallpaperRotationMinutes = 60, wallpaperOrder = "random") }
        themeManager.applyThemeToTv(
            listOf(
                WallpaperUploadAsset("sunset 1.jpg", byteArrayOf(9, 8, 7)),
                WallpaperUploadAsset("forest 2.jpg", byteArrayOf(6, 5, 4))
            )
        ).getOrThrow()
        assertTrue(adb.pushedFiles.containsKey("${CleanYourTvConstants.REMOTE_WALLPAPERS_DIR}/sunset_1.jpg"))
        assertTrue(adb.pushedFiles.containsKey("${CleanYourTvConstants.REMOTE_WALLPAPERS_DIR}/forest_2.jpg"))
        assertEquals(2, adb.restartCalls)

        // Switch to Onyx, then rollback to previous theme
        themeManager.selectPreset(CleanYourTvThemeCatalog.PRESETS.first { it.id == "onyx" })
        themeManager.updateConfig { it.copy(wallpaperEnabled = false, wallpaperRotationMinutes = 0) }
        themeManager.applyThemeToTv().getOrThrow()
        assertEquals("#000000", JSONObject(adb.pushedFiles[CleanYourTvConstants.REMOTE_CONFIG_PATH]!!.decodeToString()).getString("background_color"))

        val rolledBack = themeManager.rollbackPreviousTheme().getOrThrow()
        assertEquals("#071A18", JSONObject(rolledBack).getString("background_color"))

        // Reset removes config.json + wallpaper.jpg & safely restarts CleanYourTV
        themeManager.resetThemeOnTv().getOrThrow()
        assertFalse(adb.pushedFiles.containsKey(CleanYourTvConstants.REMOTE_CONFIG_PATH))
        assertFalse(adb.pushedFiles.containsKey(CleanYourTvConstants.REMOTE_WALLPAPER_PATH))
    }

    @Test
    fun applyThemeFailsHonestlyWhenLauncherMissingOrReadBackCorrupted() = runTest {
        // Case 1: CleanYourTV not installed -> fails loudly without pushing config
        val adbMissing = FakeHomeAdbManager(sdkVersion = 28)
        val mgrMissing = CleanYourTvThemeManager(adbMissing)
        assertTrue(mgrMissing.applyThemeToTv().isFailure)
        assertTrue(adbMissing.pushedFiles.isEmpty())

        // Case 2: Readback from TV returns corrupted/mismatched config -> fails verification!
        val adbCorrupt = FakeHomeAdbManager(sdkVersion = 28, corruptReadBack = true).apply {
            installedPackages.add(CleanYourTvConstants.LAUNCHER_PACKAGE)
        }
        val mgrCorrupt = CleanYourTvThemeManager(adbCorrupt)
        val res = mgrCorrupt.applyThemeToTv()
        assertTrue("Corrupted read-back must reject fake success", res.isFailure)
        assertEquals(0, adbCorrupt.restartCalls)
    }

    private class FakeHomeAdbManager(
        private val sdkVersion: Int,
        private val failSetHomeActivity: Boolean = false,
        private val corruptReadBack: Boolean = false
    ) : AdbConnectionManager {
        override val connectionState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
        override val pairingState = MutableStateFlow<AdbPairingState>(AdbPairingState.Idle)
        override val diagnosticCategory = MutableStateFlow(AdbDiagnosticCategory.NONE)
        override val discoveredServices = MutableStateFlow<List<DiscoveredAdbService>>(emptyList())
        override val isDiscovering = MutableStateFlow(false)
        override val savedEndpoint = MutableStateFlow<AdbEndpointConfig?>(null)
        override val lastDiagnosticDetail = MutableStateFlow("")
        override val pairingCapability = AdbPairingCapability(false, "")

        val installedPackages = linkedSetOf("com.google.android.tvlauncher", "com.android.settings")
        val enabledPackages = linkedSetOf("com.google.android.tvlauncher")
        val disabledPackages = linkedSetOf<String>()
        val registeredHomeComponents = linkedMapOf(
            "com.google.android.tvlauncher" to "com.google.android.tvlauncher/.MainActivity",
            CleanYourTvConstants.LAUNCHER_PACKAGE to CleanYourTvConstants.LAUNCHER_COMPONENT
        )
        var failEnableWith: Exception? = null
        var failEnableResult: AdbCommandResult? = null
        var setHomeResultOverride: AdbCommandResult? = null
        var ignoreEnable: Boolean = false
        var ignoreSetHomeRestore: Boolean = false
        var homeActivitiesOverride: List<String>? = null
        var resolvedHomeActivityOverride: String? = null
        val setHomeCalls = mutableListOf<String>()
        var currentHomePackage = "com.google.android.tvlauncher"
        var currentHomeActivity = "com.google.android.tvlauncher/.MainActivity"
        val pushedFiles = linkedMapOf<String, ByteArray>()
        var restartCalls = 0

        override fun startWirelessDiscovery() {}
        override fun stopWirelessDiscovery() {}
        override fun connect(host: String, port: Int, mode: AdbConnectionMode) {}
        override fun disconnect() {}
        override suspend fun pairWirelessDebugging(host: String, pairingPort: Int, pairingCode: String) =
            Result.failure<String>(UnsupportedOperationException())

        override suspend fun inspectTvDevice(): Result<TvDeviceInfo> = Result.success(
            TvDeviceInfo(
                manufacturer = "Xiaomi",
                model = "MiTV-AESP0",
                deviceName = "aquaman",
                productBoard = "aquaman",
                androidVersion = "9",
                sdkVersion = sdkVersion,
                buildId = "PI.3115",
                buildFingerprint = "Xiaomi/aquaman/aquaman:9/PI/3115:user/release-keys",
                currentHomePackage = currentHomePackage,
                currentHomeActivity = currentHomeActivity,
                currentHomeLabel = currentHomePackage
            )
        )

        override suspend fun listAllPackages() = Result.success(installedPackages.toList())
        override suspend fun listDisabledPackages() = Result.success(disabledPackages.toSet())
        override suspend fun queryHomeActivities(): Result<List<String>> {
            homeActivitiesOverride?.let { return Result.success(it) }
            val result = linkedSetOf<String>()
            if (installedPackages.contains(currentHomePackage) &&
                !disabledPackages.contains(currentHomePackage) &&
                currentHomeActivity.contains('/')
            ) {
                result.add(currentHomeActivity)
            }
            registeredHomeComponents.forEach { (pkg, component) ->
                if (installedPackages.contains(pkg) &&
                    !disabledPackages.contains(pkg) &&
                    result.none { it.substringBefore('/') == pkg }
                ) {
                    result.add(component)
                }
            }
            return Result.success(result.toList())
        }

        override suspend fun resolveHomeActivityForPackage(packageName: String): Result<String?> {
            resolvedHomeActivityOverride?.let { return Result.success(it) }
            if (homeActivitiesOverride != null) {
                return Result.success(homeActivitiesOverride!!.firstOrNull { it.substringBefore('/') == packageName })
            }
            return queryHomeActivities().map { list -> list.firstOrNull { it.substringBefore('/') == packageName } }
        }

        override suspend fun disablePackageForUser(packageName: String): Result<AdbCommandResult> {
            disabledPackages.add(packageName)
            enabledPackages.remove(packageName)
            return Result.success(AdbCommandResult(0, "disabled"))
        }

        override suspend fun enablePackage(packageName: String): Result<AdbCommandResult> {
            failEnableWith?.let { return Result.failure(it) }
            failEnableResult?.let { return Result.success(it) }
            if (!ignoreEnable) {
                disabledPackages.remove(packageName)
                enabledPackages.add(packageName)
            }
            return Result.success(AdbCommandResult(0, "enabled"))
        }

        override suspend fun launchHomeChooserOrOpenHome() = Result.success(AdbCommandResult(0, "Launched HOME"))

        override suspend fun setDefaultHomeActivity(componentName: String): Result<AdbCommandResult> {
            setHomeCalls.add(componentName)
            if (failSetHomeActivity) {
                return Result.failure(IOException("SecurityException: Permission denied by OEM firmware"))
            }
            setHomeResultOverride?.let { return Result.success(it) }
            if (!ignoreSetHomeRestore) {
                currentHomeActivity = componentName
                currentHomePackage = componentName.substringBefore('/')
            }
            return Result.success(AdbCommandResult(0, "Success"))
        }

        override suspend fun restartCleanYourTvLauncher(): Result<AdbCommandResult> {
            restartCalls++
            return Result.success(AdbCommandResult(0, "Restarted com.gathin.tvhome"))
        }

        override suspend fun installApk(apkBytes: ByteArray): Result<AdbCommandResult> {
            installedPackages.add(CleanYourTvConstants.LAUNCHER_PACKAGE)
            return Result.success(AdbCommandResult(0, "Success"))
        }

        override suspend fun pushAppConfigFile(remotePath: String, content: ByteArray): Result<AdbCommandResult> {
            pushedFiles[remotePath] = content
            return Result.success(AdbCommandResult(0, "OK"))
        }

        override suspend fun readRemoteFile(remotePath: String): Result<ByteArray> {
            if (corruptReadBack) {
                return Result.success("""{"wallpaper":"wrong_string_type","theme_id":"bad"}""".toByteArray())
            }
            val bytes = pushedFiles[remotePath] ?: return Result.failure(IOException("Missing $remotePath"))
            return Result.success(bytes)
        }

        override suspend fun remoteFileExists(remotePath: String): Result<Boolean> {
            return Result.success(pushedFiles.containsKey(remotePath))
        }

        override suspend fun ensureRemoteDirectory(remoteDir: String) = Result.success(AdbCommandResult(0, "OK"))

        override suspend fun removeRemoteFile(remotePath: String): Result<AdbCommandResult> {
            pushedFiles.remove(remotePath)
            return Result.success(AdbCommandResult(0, "OK"))
        }

        override suspend fun checkFilePushSupported() = Result.success(true)
    }
}
