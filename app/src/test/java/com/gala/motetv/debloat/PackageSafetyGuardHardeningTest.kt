package com.gala.motetv.debloat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gala.motetv.adb.AdbCommandResult
import com.gala.motetv.adb.AdbConnectionManager
import com.gala.motetv.adb.AdbConnectionManagerImpl
import com.gala.motetv.adb.AdbConnectionMode
import com.gala.motetv.adb.AdbConnectionState
import com.gala.motetv.adb.AdbCredentialStore
import com.gala.motetv.adb.AdbDiagnosticCategory
import com.gala.motetv.adb.AdbEndpointConfig
import com.gala.motetv.adb.AdbPairingCapability
import com.gala.motetv.adb.AdbPairingState
import com.gala.motetv.adb.AdbSession
import com.gala.motetv.adb.DiscoveredAdbService
import com.gala.motetv.adb.TvDeviceInfo
import com.gala.motetv.tvhome.CleanYourTvConstants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
class PackageSafetyGuardHardeningTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("motetv_debloat_records", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun stickDevice(activeHome: String = "com.google.android.tvlauncher") = TvDeviceInfo(
        manufacturer = "Xiaomi",
        model = "MiTV-AESP0",
        deviceName = "aquaman",
        productBoard = "aquaman",
        androidVersion = "9",
        sdkVersion = 28,
        buildId = "PI.3115",
        buildFingerprint = "Xiaomi/aquaman/aquaman:9/PI/3115:user/release-keys",
        currentHomePackage = activeHome,
        currentHomeActivity = "$activeHome/.MainActivity",
        currentHomeLabel = activeHome
    )

    @Test
    fun issue884AndCoreAndroidPackagesAreHardBlockedAcrossAllVariants() {
        val mustBlock = listOf(
            // A. Permission & package-management variants
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "android.packageinstaller",
            // B. Extension services, module metadata, CTS shims
            "com.google.android.ext.services",
            "com.android.ext.services",
            "android.ext.services",
            "com.google.android.ext.shared",
            "com.android.ext.shared",
            "android.ext.shared",
            "com.google.android.modulemetadata",
            "com.android.modulemetadata",
            "com.android.cts.priv.ctsshim",
            "com.android.cts.ctsshim",
            "com.android.apex.cts.shim",
            // C. Runtime overlay module prefix/family variants
            "com.google.android.overlay.modules.permissioncontroller",
            "com.google.android.overlay.modules.ext.services",
            "com.google.android.overlay.modules.modulemetadata",
            "com.google.android.overlay.modules.permissioncontroller.forframework",
            "com.android.overlay.systemui",
            "com.google.android.overlay.tv",
            "android.overlay.framework",
            "com.xiaomi.mitv.overlay.system",
            // D. Setup & provisioning
            "com.google.android.tungsten.setupwraith",
            "com.google.android.setupwizard",
            "com.android.setupwizard",
            "com.android.provision",
            "com.google.android.partnersetup",
            "com.xiaomi.mitv.setup",
            // E. Stock HOME launchers
            "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx",
            "com.mitv.tvhome",
            "com.android.tv.launcher",
            // F. Framework, SystemUI, Settings, ADB/Shell, Network, BT, IME, Accessibility, GMS, WebView
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.tv.settings",
            "com.android.shell",
            "com.android.providers.settings",
            "com.android.providers.media",
            "com.android.networkstack",
            "com.google.android.networkstack",
            "com.android.wifi.resources",
            "com.android.bluetooth",
            "com.google.android.inputmethod.latin",
            "com.google.android.marvin.talkback",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending",
            "com.google.android.webview",
            "com.xiaomi.mitv.settings"
        )

        val deviceCleanActive = stickDevice(activeHome = CleanYourTvConstants.LAUNCHER_PACKAGE)
        val installed = mustBlock.toSet() + CleanYourTvConstants.LAUNCHER_PACKAGE

        mustBlock.forEach { pkg ->
            val (alwaysBlocked, reason) = PackageSafetyGuard.isAlwaysBlockedPackage(pkg)
            assertTrue("Expected $pkg to be permanently blocked", alwaysBlocked)
            assertFalse("Block reason missing for $pkg", reason.isNullOrBlank())

            // Must stay blocked even if CleanYourTV Home is claimed active
            val decision = PackageSafetyGuard.evaluateDisableCandidate(
                packageName = pkg,
                deviceInfo = deviceCleanActive,
                installedPackages = installed,
                verifiedReplacementHomeInstalled = true,
                verifiedReplacementHomeActive = true,
                systemPackages = emptySet()
            )
            assertFalse("Expected evaluateDisableCandidate to reject $pkg even with replacement HOME active", decision.allowed)
        }
    }

    @Test
    fun normalizesCaseAndWhitespaceAndAvoidsFalsePositivesOnThirdPartyApps() {
        val mixedCaseBlocked = listOf(
            " COM.GOOGLE.ANDROID.TUNGSTEN.SETUPWRAITH ",
            "Com.Google.Android.Overlay.Modules.ModuleMetadata",
            "COM.ANDROID.PERMISSIONCONTROLLER",
            "  com.MITV.tvhome "
        )
        mixedCaseBlocked.forEach { raw ->
            assertTrue("Case/whitespace variant '$raw' must still be blocked", PackageSafetyGuard.isAlwaysBlockedPackage(raw).first)
        }

        // Negative checks: unrelated third-party user apps containing substrings must NOT be falsely blocked
        val benignThirdPartyApps = listOf(
            "com.example.hdmichecker",
            "org.thirdparty.screenoverlay",
            "com.custom.wifianalyzer",
            "com.userapp.remotehelper",
            "com.thirdparty.extplayer",
            "tv.plex.app"
        )
        benignThirdPartyApps.forEach { app ->
            val decision = PackageSafetyGuard.evaluateDisableCandidate(
                packageName = app,
                deviceInfo = stickDevice(),
                installedPackages = setOf(app),
                verifiedReplacementHomeInstalled = false,
                verifiedReplacementHomeActive = false,
                systemPackages = emptySet()
            )
            assertTrue("Third-party app $app should remain manually reviewable (allowed=true)", decision.allowed)
            assertTrue("Third-party app $app must require explicit prompt", decision.requiresExplicitOptionalPrompt)
        }
    }

    @Test
    fun unknownSystemPackagesFailClosedWhileUnknownThirdPartyAppsStayManualReviewOnly() {
        val unknownSystemNamespaces = listOf(
            "com.android.mystery.daemon",
            "com.google.android.unlisted.framework.service",
            "android.unlisted.helper",
            "com.xiaomi.unknown.systemservice",
            "com.oem.hal.reported.as.system" // not OEM prefix, but returned by pm list packages -s
        )
        val systemPkgsFromTv = setOf("com.oem.hal.reported.as.system")
        val scanner = TvPackageScanner(
            adbManager = DummyNoopAdbManager(),
            repository = DebloatRepository(null)
        )

        val scan = scanner.buildScanResult(
            deviceInfo = stickDevice(),
            installedPackages = unknownSystemNamespaces + listOf("com.indie.tvgame", "com.miui.tv.analytics"),
            disabledPackages = emptySet(),
            systemPackages = systemPkgsFromTv
        )
        val byPkg = scan.packages.associateBy { it.packageName }

        unknownSystemNamespaces.forEach { sysPkg ->
            val scanned = byPkg.getValue(sysPkg)
            assertEquals("Unknown system package $sysPkg must fail closed", DebloatRecommendation.NEVER_DISABLE, scanned.recommendation)
            assertEquals(RiskLevel.CRITICAL_BLOCKED, scanned.riskLevel)
            assertTrue("$sysPkg must be blocked from disable", scanned.isBlockedFromDisable)
        }

        // Unknown 3rd-party user app: manually reviewable, never auto-disabled
        val thirdParty = byPkg.getValue("com.indie.tvgame")
        assertEquals(PackageCategory.UNKNOWN_UNVERIFIED, thirdParty.category)
        assertEquals(DebloatRecommendation.OPTIONAL_ASK_USER, thirdParty.recommendation)
        assertFalse(thirdParty.isBlockedFromDisable)
        assertTrue(thirdParty.requiresOptionalPrompt)
    }

    @Test
    fun nonBypassableAcrossSelectionImportDebloatManagerAndAdbExecutionBoundary() = runTest {
        val protectedTargets = listOf(
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.google.android.ext.services",
            "com.google.android.ext.shared",
            "com.google.android.modulemetadata",
            "com.google.android.overlay.modules.permissioncontroller",
            "com.google.android.tungsten.setupwraith",
            "com.google.android.tvlauncher",
            "com.mitv.tvhome",
            "com.android.mystery.system.service",
            "com.oem.system.reported.service"
        )

        val installed = protectedTargets + listOf(
            CleanYourTvConstants.LAUNCHER_PACKAGE,
            "com.miui.tv.analytics",
            "com.indie.tvgame"
        )
        val systemPkgs = (protectedTargets - "com.indie.tvgame").toSet()

        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val fakeSession = RecordingFakeAdbSession(
            installed = installed,
            system = systemPkgs,
            currentHome = CleanYourTvConstants.LAUNCHER_PACKAGE
        )
        val adbImpl = AdbConnectionManagerImpl(
            context = null,
            credentialStore = AdbCredentialStore(null),
            ioDispatcher = dispatcher,
            scope = scope,
            sessionFactory = { _, _, _, _ -> fakeSession }
        )
        adbImpl.connect("192.168.1.50", 5555, AdbConnectionMode.NETWORK_LEGACY_5555)
        advanceUntilIdle()

        val repo = DebloatRepository(context)
        val scanner = TvPackageScanner(adbImpl, repo)
        val manager = DebloatManager(adbImpl, scanner, repo)

        val scan = manager.scanTv().getOrThrow()
        assertFalse(scan.packages.isEmpty())

        // 1. Manual selection bypass attempt fails
        protectedTargets.forEach { pkg ->
            manager.togglePackageSelection(pkg)
            assertFalse("Manual selection must reject $pkg", manager.selectedPackages.value.contains(pkg))
        }

        // 2. Recommended safe selection only picks verified SAFE_TO_DISABLE (never blocked, optional, or unknown)
        manager.selectRecommendedSafePackages()
        assertEquals(setOf("com.miui.tv.analytics"), manager.selectedPackages.value)

        // 3. Imported list bypass attempt fails cleanly
        val importAttempt = manager.importPackageListForReview(
            listOf("com.miui.tv.analytics", "com.google.android.tungsten.setupwraith", "com.mitv.tvhome")
        )
        assertTrue("Imported package list containing protected packages must be rejected", importAttempt.isFailure)

        // 4. Direct call to DebloatManager.disablePackagesWithBackup fails BEFORE touching ADB
        protectedTargets.forEach { pkg ->
            val directDebloat = manager.disablePackagesWithBackup(
                packages = listOf(pkg),
                verifiedReplacementHomeInstalled = true,
                verifiedReplacementHomeActive = true
            )
            assertTrue("Direct DebloatManager call must block $pkg", directDebloat.isFailure)
            assertTrue(directDebloat.exceptionOrNull() is SecurityException)
        }

        // 5. Lowest-level ADB execution boundary (AdbConnectionManagerImpl.disablePackageForUser) fails
        //    AND sends ZERO pm disable/hide/uninstall shell commands to FakeAdbSession!
        protectedTargets.forEach { pkg ->
            val directAdb = adbImpl.disablePackageForUser(pkg)
            assertTrue("ADB boundary must block $pkg", directAdb.isFailure)
            assertTrue("Expected SecurityException for $pkg", directAdb.exceptionOrNull() is SecurityException)
        }
        // Active CleanYourTV HOME itself must also be blocked at ADB boundary
        assertTrue(adbImpl.disablePackageForUser(CleanYourTvConstants.LAUNCHER_PACKAGE).isFailure)

        assertTrue(
            "Zero destructive pm shell commands may reach ADB session: ${fakeSession.destructiveCommands}",
            fakeSession.destructiveCommands.isEmpty()
        )
    }

    @Test
    fun staticCatalogValidationEnsuresNoHardBlockedPackageIsSafeOrOptional() {
        val violations = mutableListOf<String>()
        CleanYourTvPackageCatalog.KNOWN_PACKAGES.forEach { descriptor ->
            val (blocked, reason) = PackageSafetyGuard.isAlwaysBlockedPackage(descriptor.packageName)
            if (blocked && descriptor.recommendation != DebloatRecommendation.NEVER_DISABLE) {
                violations.add("${descriptor.packageName} is hard-blocked ($reason) but catalog has ${descriptor.recommendation}")
            }
            if (CleanYourTvPackageCatalog.STOCK_LAUNCHER_PACKAGES.contains(descriptor.packageName.lowercase()) &&
                descriptor.recommendation != DebloatRecommendation.NEVER_DISABLE
            ) {
                violations.add("Stock launcher ${descriptor.packageName} must be NEVER_DISABLE")
            }
        }
        assertTrue("Catalog safety policy violations found:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    private class RecordingFakeAdbSession(
        private val installed: List<String>,
        private val system: Set<String>,
        private val currentHome: String
    ) : AdbSession {
        override val banner: String = "device::ro.product.model=MiTV-AESP0;"
        override var isConnected: Boolean = true
        val destructiveCommands = mutableListOf<String>()

        override suspend fun openShell(command: String): AdbCommandResult {
            val trimmed = command.trim()
            if (trimmed.contains("pm disable") ||
                trimmed.contains("pm hide") ||
                trimmed.contains("pm uninstall") ||
                trimmed.contains("pm suspend")
            ) {
                destructiveCommands.add(trimmed)
            }
            return when {
                trimmed.contains("getprop ro.product.manufacturer") -> AdbCommandResult(
                    0,
                    "MFG=Xiaomi\nMOD=MiTV-AESP0\nDEV=aquaman\nBRD=aquaman\nREL=9\nSDK=28\nBID=PI.3115\nFGP=Xiaomi/aquaman/aquaman:9/PI/3115:user/release-keys"
                )
                trimmed.contains("resolve-activity") -> AdbCommandResult(0, "$currentHome/.MainActivity")
                trimmed == "pm list packages" -> AdbCommandResult(0, installed.joinToString("\n") { "package:$it" })
                trimmed == "pm list packages -s" -> AdbCommandResult(0, system.joinToString("\n") { "package:$it" })
                trimmed == "pm list packages -d" -> AdbCommandResult(0, "")
                else -> AdbCommandResult(0, "OK")
            }
        }

        override suspend fun pushFile(remotePath: String, content: ByteArray, mode: Int) = AdbCommandResult(0, "OK")
        override suspend fun pullFile(remotePath: String): ByteArray = throw IOException("Not found")
        override fun close() {
            isConnected = false
        }
    }

    private class DummyNoopAdbManager : AdbConnectionManager {
        override val connectionState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
        override val pairingState = MutableStateFlow<AdbPairingState>(AdbPairingState.Idle)
        override val diagnosticCategory = MutableStateFlow(AdbDiagnosticCategory.NONE)
        override val discoveredServices = MutableStateFlow<List<DiscoveredAdbService>>(emptyList())
        override val isDiscovering = MutableStateFlow(false)
        override val savedEndpoint = MutableStateFlow<AdbEndpointConfig?>(null)
        override val lastDiagnosticDetail = MutableStateFlow("")
        override val pairingCapability = AdbPairingCapability(false, "")
        override fun startWirelessDiscovery() {}
        override fun stopWirelessDiscovery() {}
        override fun connect(host: String, port: Int, mode: AdbConnectionMode) {}
        override fun disconnect() {}
        override suspend fun pairWirelessDebugging(host: String, pairingPort: Int, pairingCode: String) =
            Result.failure<String>(UnsupportedOperationException())
        override suspend fun inspectTvDevice() = Result.failure<TvDeviceInfo>(IllegalStateException())
        override suspend fun listAllPackages() = Result.success(emptyList<String>())
        override suspend fun listDisabledPackages() = Result.success(emptySet<String>())
        override suspend fun disablePackageForUser(packageName: String) = Result.success(AdbCommandResult(0, ""))
        override suspend fun enablePackage(packageName: String) = Result.success(AdbCommandResult(0, ""))
        override suspend fun launchHomeChooserOrOpenHome() = Result.success(AdbCommandResult(0, ""))
        override suspend fun setDefaultHomeActivity(componentName: String) = Result.success(AdbCommandResult(0, ""))
        override suspend fun restartCleanYourTvLauncher() = Result.success(AdbCommandResult(0, ""))
        override suspend fun installApk(apkBytes: ByteArray) = Result.success(AdbCommandResult(0, ""))
        override suspend fun pushAppConfigFile(remotePath: String, content: ByteArray) = Result.success(AdbCommandResult(0, ""))
        override suspend fun readRemoteFile(remotePath: String) = Result.failure<ByteArray>(IOException())
        override suspend fun remoteFileExists(remotePath: String) = Result.success(false)
        override suspend fun ensureRemoteDirectory(remoteDir: String) = Result.success(AdbCommandResult(0, ""))
        override suspend fun removeRemoteFile(remotePath: String) = Result.success(AdbCommandResult(0, ""))
        override suspend fun checkFilePushSupported() = Result.success(true)
    }
}
