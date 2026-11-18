package com.MyMote.tv.debloat

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
import com.MyMote.tv.tvhome.CleanYourTvConstants
import com.MyMote.tv.tvhome.CleanYourTvLauncherManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class TvRecoveryCoordinatorTest {

    private lateinit var context: Context

    @Before
    fun clearPrefsBeforeEachTest() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("motetv_debloat_records", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("motetv_tvhome_recovery", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun recoveryAfterAppRestartWithSavedOriginalHomeRestoresPackagesAndVerifiesHome() = runTest {
        val adb = FakeRecoveryAdbManager(
            installed = linkedSetOf(
                "com.google.android.tvlauncher",
                "com.miui.tv.analytics",
                "com.xiaomi.statistic"
            ),
            currentHomePkg = "com.google.android.tvlauncher",
            currentHomeAct = "com.google.android.tvlauncher/.MainActivity"
        )

        // Session 1: User records stock HOME, installs & activates CleanYourTV, disables 2 analytics packages
        run {
            val repo1 = DebloatRepository(context)
            val debloat1 = DebloatManager(adb, TvPackageScanner(adb, repo1), repo1)
            val launcher1 = CleanYourTvLauncherManager(context, adb)

            launcher1.refreshLauncherStatus().getOrThrow()
            launcher1.installCleanYourTvApk(byteArrayOf(0x50, 0x4B, 0x03, 0x04)).getOrThrow()
            launcher1.activateCleanYourTvAsHome().getOrThrow()
            assertEquals(CleanYourTvConstants.LAUNCHER_PACKAGE, adb.currentHomePkg)

            debloat1.scanTv().getOrThrow()
            debloat1.disablePackagesWithBackup(listOf("com.miui.tv.analytics", "com.xiaomi.statistic")).getOrThrow()
            assertEquals(setOf("com.miui.tv.analytics", "com.xiaomi.statistic"), repo1.getModifiedPackages())
        }

        // Session 2: Simulate force-close and reopen MoteTV with brand-new instances sharing only SharedPreferences
        val repo2 = DebloatRepository(context)
        val debloat2 = DebloatManager(adb, TvPackageScanner(adb, repo2), repo2)
        val launcher2 = CleanYourTvLauncherManager(context, adb)
        val coordinator2 = TvRecoveryCoordinator(debloat2, launcher2)

        // Persisted original HOME must be available immediately before calling refreshLauncherStatus()
        assertEquals("com.google.android.tvlauncher", launcher2.launcherStatus.value.originalHomePackage)
        assertEquals(2, debloat2.modifiedPackages.value.size)

        val report = coordinator2.recoverTv().getOrThrow()
        assertTrue(report.isCompleteSuccess)
        assertEquals(2, report.trackedPackagesCount)
        assertEquals(2, report.restoredPackagesCount)
        assertTrue(report.failedPackages.isEmpty())
        assertTrue(report.homeRestoreAttempted)
        assertTrue(report.homeRestored)
        assertEquals("com.google.android.tvlauncher", report.verifiedActiveHomePackage)
        assertEquals("com.google.android.tvlauncher", adb.currentHomePkg)
        assertTrue(repo2.getModifiedPackages().isEmpty())
    }

    @Test
    fun recoveryWhenNoOriginalHomeRecordExistsRestoresPackagesWithoutCallingSetHome() = runTest {
        val adb = FakeRecoveryAdbManager(
            installed = linkedSetOf(
                "com.google.android.tvlauncher",
                CleanYourTvConstants.LAUNCHER_PACKAGE,
                "com.miui.tv.analytics"
            ),
            currentHomePkg = CleanYourTvConstants.LAUNCHER_PACKAGE,
            currentHomeAct = CleanYourTvConstants.LAUNCHER_COMPONENT
        )
        // CleanYourTV is already HOME before MoteTV ever ran -> no original HOME record was persisted
        val repo = DebloatRepository(context)
        val debloat = DebloatManager(adb, TvPackageScanner(adb, repo), repo)
        val launcher = CleanYourTvLauncherManager(context, adb)
        val coordinator = TvRecoveryCoordinator(debloat, launcher)

        // Direct call fails clearly when no valid original HOME exists
        assertTrue(launcher.restoreOriginalHomeLauncher().isFailure)
        assertTrue(adb.setHomeCalls.isEmpty())

        // Disable & track a package when original HOME was already CleanYourTV (so backup has CleanYourTV, not stock)
        debloat.scanTv().getOrThrow()
        debloat.disablePackagesWithBackup(listOf("com.miui.tv.analytics")).getOrThrow()

        val result = coordinator.recoverTv()
        // Package restored cleanly, setDefaultHomeActivity was NOT invoked with a fake/CleanYourTV target
        assertTrue(adb.setHomeCalls.isEmpty())
        assertFalse(adb.disabled.contains("com.miui.tv.analytics"))
        // Since CleanYourTV remains active with no saved stock HOME record, incomplete warning is reported honestly
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull() as TvRecoveryException
        assertFalse(err.report.homeRestoreAttempted)
        assertEquals(1, err.report.restoredPackagesCount)
    }

    @Test
    fun originalLauncherIsEnabledAndVerifiedBeforeSettingAsDefaultHome() = runTest {
        val adb = FakeRecoveryAdbManager(
            installed = linkedSetOf("com.google.android.tvlauncher", CleanYourTvConstants.LAUNCHER_PACKAGE),
            initialDisabled = linkedSetOf("com.google.android.tvlauncher"),
            currentHomePkg = CleanYourTvConstants.LAUNCHER_PACKAGE,
            currentHomeAct = CleanYourTvConstants.LAUNCHER_COMPONENT
        )
        // Persist original HOME directly into prefs as if captured earlier before launcher became disabled
        context.getSharedPreferences("motetv_tvhome_recovery", Context.MODE_PRIVATE).edit()
            .putString("orig_home_pkg", "com.google.android.tvlauncher")
            .putString("orig_home_act", "com.google.android.tvlauncher/.MainActivity")
            .putBoolean("orig_home_valid", true)
            .commit()

        val repo = DebloatRepository(context)
        val debloat = DebloatManager(adb, TvPackageScanner(adb, repo), repo)
        val launcher = CleanYourTvLauncherManager(context, adb)
        val coordinator = TvRecoveryCoordinator(debloat, launcher)

        val report = coordinator.recoverTv().getOrThrow()
        assertTrue(report.isCompleteSuccess)
        assertTrue(report.homeRestored)
        assertEquals("com.google.android.tvlauncher", adb.currentHomePkg)
        assertFalse(adb.disabled.contains("com.google.android.tvlauncher"))

        val enableIdx = adb.operationLog.indexOf("ENABLE:com.google.android.tvlauncher")
        val setHomeIdx = adb.operationLog.indexOf("SET_HOME:com.google.android.tvlauncher/.MainActivity")
        assertTrue("enablePackage must run ($adb)", enableIdx >= 0)
        assertTrue("setDefaultHomeActivity must run ($adb)", setHomeIdx >= 0)
        assertTrue("enablePackage MUST execute before setDefaultHomeActivity", enableIdx < setHomeIdx)
    }

    @Test
    fun packageRestorationSucceedsButHomeRestorationFailsReportsFailureAndPreservesRestoredPackages() = runTest {
        val adb = FakeRecoveryAdbManager(
            installed = linkedSetOf("com.google.android.tvlauncher", "com.miui.tv.analytics", "com.xiaomi.statistic"),
            currentHomePkg = "com.google.android.tvlauncher",
            currentHomeAct = "com.google.android.tvlauncher/.MainActivity"
        )
        val repo1 = DebloatRepository(context)
        val debloat1 = DebloatManager(adb, TvPackageScanner(adb, repo1), repo1)
        val launcher1 = CleanYourTvLauncherManager(context, adb)
        launcher1.refreshLauncherStatus().getOrThrow()
        launcher1.installCleanYourTvApk(byteArrayOf(0x50, 0x4B)).getOrThrow()
        launcher1.activateCleanYourTvAsHome().getOrThrow()
        debloat1.scanTv().getOrThrow()
        debloat1.disablePackagesWithBackup(listOf("com.miui.tv.analytics", "com.xiaomi.statistic")).getOrThrow()

        // Simulate firmware refusing setDefaultHomeActivity on restore after app restart
        adb.failSetHome = true
        val repo2 = DebloatRepository(context)
        val debloat2 = DebloatManager(adb, TvPackageScanner(adb, repo2), repo2)
        val launcher2 = CleanYourTvLauncherManager(context, adb)
        val coordinator2 = TvRecoveryCoordinator(debloat2, launcher2)

        val res = coordinator2.recoverTv()
        assertTrue("Complete recovery must fail when HOME restore fails", res.isFailure)
        val err = res.exceptionOrNull() as TvRecoveryException
        assertEquals(2, err.report.restoredPackagesCount)
        assertTrue(err.report.failedPackages.isEmpty())
        assertTrue(err.report.homeRestoreAttempted)
        assertFalse(err.report.homeRestored)
        assertNotNull(err.report.homeRestoreError)
        // Package restoration progress MUST be persisted (not rolled back or lost)
        assertTrue(repo2.getModifiedPackages().isEmpty())
        assertTrue(err.message!!.contains("restored 2/2"))
        assertTrue(err.message!!.contains("HOME restore FAILED"))
    }

    @Test
    fun partialPackageRestorationFailurePreservesPartialProgressRestoresHomeAndNamesFailure() = runTest {
        val adb = FakeRecoveryAdbManager(
            installed = linkedSetOf(
                "com.google.android.tvlauncher",
                "com.miui.tv.analytics",
                "com.xiaomi.statistic",
                "com.mitv.tvhome.atv"
            ),
            currentHomePkg = "com.google.android.tvlauncher",
            currentHomeAct = "com.google.android.tvlauncher/.MainActivity"
        )
        val repo1 = DebloatRepository(context)
        val debloat1 = DebloatManager(adb, TvPackageScanner(adb, repo1), repo1)
        val launcher1 = CleanYourTvLauncherManager(context, adb)
        launcher1.refreshLauncherStatus().getOrThrow()
        launcher1.installCleanYourTvApk(byteArrayOf(0x50, 0x4B)).getOrThrow()
        launcher1.activateCleanYourTvAsHome().getOrThrow()
        debloat1.scanTv().getOrThrow()
        debloat1.disablePackagesWithBackup(
            listOf("com.miui.tv.analytics", "com.xiaomi.statistic", "com.mitv.tvhome.atv")
        ).getOrThrow()

        // Now one package fails pm enable on restart while the other 2 + HOME succeed
        adb.failEnablePackages.add("com.xiaomi.statistic")
        val repo2 = DebloatRepository(context)
        val debloat2 = DebloatManager(adb, TvPackageScanner(adb, repo2), repo2)
        val launcher2 = CleanYourTvLauncherManager(context, adb)
        val coordinator2 = TvRecoveryCoordinator(debloat2, launcher2)

        val res = coordinator2.recoverTv()
        assertTrue("Partial package restore failure must NOT report complete recovery", res.isFailure)
        val err = res.exceptionOrNull() as TvRecoveryException
        assertEquals(3, err.report.trackedPackagesCount)
        assertEquals(2, err.report.restoredPackagesCount)
        assertEquals(listOf("com.xiaomi.statistic"), err.report.failedPackages)
        assertEquals(setOf("com.xiaomi.statistic"), repo2.getModifiedPackages())
        assertTrue("HOME should still be restored even when one package failed", err.report.homeRestored)
        assertEquals("com.google.android.tvlauncher", adb.currentHomePkg)
        assertTrue(err.message!!.contains("restored 2/3"))
        assertTrue(err.message!!.contains("com.xiaomi.statistic"))
    }

    @Test
    fun recoveryAfterCrashBeforeModifiedTrackingRestoresDisabledTargetsWithoutTouchingPreDisabled() = runTest {
        val adb = FakeRecoveryAdbManager(
            installed = linkedSetOf(
                "com.google.android.tvlauncher",
                CleanYourTvConstants.LAUNCHER_PACKAGE,
                "com.miui.tv.analytics",
                "com.xiaomi.statistic",
                "com.xm.webcontent"
            ),
            initialDisabled = linkedSetOf("com.xm.webcontent"),
            currentHomePkg = "com.google.android.tvlauncher",
            currentHomeAct = "com.google.android.tvlauncher/.MainActivity"
        )

        // Session 1: Backup + switch HOME + simulate crash right after TV disables analytics BEFORE markPackageModified
        val repo1 = DebloatRepository(context)
        val debloat1 = DebloatManager(adb, TvPackageScanner(adb, repo1), repo1)
        val launcher1 = CleanYourTvLauncherManager(context, adb)
        launcher1.refreshLauncherStatus().getOrThrow()
        launcher1.activateCleanYourTvAsHome().getOrThrow()
        debloat1.scanTv().getOrThrow()
        val backup = debloat1.createBackup(listOf("com.miui.tv.analytics", "com.xiaomi.statistic")).getOrThrow()
        repo1.upsertPendingOperation(
            PendingDebloatOperation(
                operationId = "crash-op-1",
                backupId = backup.backupId,
                packageName = "com.miui.tv.analytics",
                wasOriginallyDisabled = false,
                deviceManufacturer = "Xiaomi",
                deviceModel = "MiTV-AESP0",
                deviceFingerprint = backup.deviceInfo.buildFingerprint,
                createdAtMs = 1000L,
                status = PendingOperationStatus.PENDING_DISABLE
            )
        ).getOrThrow()
        // Simulate ADB having disabled both packages before crash interrupted markPackageModified
        adb.disabled.add("com.miui.tv.analytics")
        adb.disabled.add("com.xiaomi.statistic")
        assertTrue(repo1.getModifiedPackages().isEmpty())

        // Session 2: App restarted -> TvRecoveryCoordinator must recover BOTH disabled targets + HOME, NEVER touching com.xm.webcontent
        val repo2 = DebloatRepository(context)
        val debloat2 = DebloatManager(adb, TvPackageScanner(adb, repo2), repo2)
        val launcher2 = CleanYourTvLauncherManager(context, adb)
        val coordinator2 = TvRecoveryCoordinator(debloat2, launcher2)

        val report = coordinator2.recoverTv().getOrThrow()
        assertTrue(report.isCompleteSuccess)
        assertEquals(2, report.trackedPackagesCount)
        assertEquals(2, report.restoredPackagesCount)
        assertFalse(adb.disabled.contains("com.miui.tv.analytics"))
        assertFalse(adb.disabled.contains("com.xiaomi.statistic"))
        assertTrue("Pre-disabled package com.xm.webcontent must remain disabled", adb.disabled.contains("com.xm.webcontent"))
        assertTrue(repo2.getPendingOperations().isEmpty())
        assertTrue(repo2.getModifiedPackages().isEmpty())
        assertEquals("com.google.android.tvlauncher", adb.currentHomePkg)
    }

    @Test
    fun partialOrUnverifiedOriginalHomeRecordFailsClosedDuringRecovery() = runTest {
        val adb = FakeRecoveryAdbManager(
            installed = linkedSetOf(
                "com.google.android.tvlauncher",
                CleanYourTvConstants.LAUNCHER_PACKAGE,
                "com.miui.tv.analytics"
            ),
            initialDisabled = linkedSetOf("com.miui.tv.analytics"),
            currentHomePkg = CleanYourTvConstants.LAUNCHER_PACKAGE,
            currentHomeAct = CleanYourTvConstants.LAUNCHER_COMPONENT
        )
        // Corrupt/unverified backup: pkg present without orig_home_act or orig_home_valid=true
        context.getSharedPreferences("motetv_tvhome_recovery", Context.MODE_PRIVATE).edit()
            .putString("orig_home_pkg", "com.google.android.tvlauncher")
            .putBoolean("orig_home_valid", false)
            .commit()

        val repo = DebloatRepository(context)
        repo.markPackageModified("com.miui.tv.analytics").getOrThrow()
        val debloat = DebloatManager(adb, TvPackageScanner(adb, repo), repo)
        val launcher = CleanYourTvLauncherManager(context, adb)
        val coordinator = TvRecoveryCoordinator(debloat, launcher)

        val res = coordinator.recoverTv()
        assertTrue("Recovery must report unverified/missing original HOME cleanly", res.isFailure)
        val err = res.exceptionOrNull() as TvRecoveryException
        assertEquals(listOf("com.miui.tv.analytics"), err.report.restoredPackages)
        assertFalse(adb.disabled.contains("com.miui.tv.analytics"))
        assertFalse(err.report.homeRestoreAttempted)
        assertTrue("Must NOT guess HOME component when persisted record is unverified", adb.setHomeCalls.isEmpty())
    }

    @Test
    fun repeatedRecoveryIsIdempotentAndReportsDistinctStatusBreakdown() = runTest {
        val adb = FakeRecoveryAdbManager(
            installed = linkedSetOf(
                "com.google.android.tvlauncher",
                "com.miui.tv.analytics",
                "com.xiaomi.statistic"
            ),
            currentHomePkg = "com.google.android.tvlauncher",
            currentHomeAct = "com.google.android.tvlauncher/.MainActivity"
        )
        val repo = DebloatRepository(context)
        val debloat = DebloatManager(adb, TvPackageScanner(adb, repo), repo)
        val launcher = CleanYourTvLauncherManager(context, adb)
        val coordinator = TvRecoveryCoordinator(debloat, launcher)

        launcher.refreshLauncherStatus().getOrThrow()
        launcher.installCleanYourTvApk(byteArrayOf(0x50, 0x4B)).getOrThrow()
        launcher.activateCleanYourTvAsHome().getOrThrow()
        debloat.scanTv().getOrThrow()
        debloat.disablePackagesWithBackup(listOf("com.miui.tv.analytics", "com.xiaomi.statistic")).getOrThrow()

        // First recovery: one package fails
        adb.failEnablePackages.add("com.xiaomi.statistic")
        val firstRes = coordinator.recoverTv()
        assertTrue(firstRes.isFailure)
        val err1 = firstRes.exceptionOrNull() as TvRecoveryException
        assertEquals(listOf("com.miui.tv.analytics"), err1.report.restoredPackages)
        assertEquals(listOf("com.xiaomi.statistic"), err1.report.failedPackages)
        assertTrue(err1.report.pendingPackages.isEmpty())
        assertTrue(err1.report.unverifiedPackages.isEmpty())

        // Second recovery after transient issue clears: completes remaining package idempotently
        adb.failEnablePackages.remove("com.xiaomi.statistic")
        val secondReport = coordinator.recoverTv().getOrThrow()
        assertTrue(secondReport.isCompleteSuccess)
        assertEquals(listOf("com.xiaomi.statistic"), secondReport.restoredPackages)
        assertTrue(secondReport.failedPackages.isEmpty())
        assertTrue(repo.getModifiedPackages().isEmpty())

        // Third recovery with nothing modified left: idempotent clean success
        val thirdReport = coordinator.recoverTv().getOrThrow()
        assertTrue(thirdReport.isCompleteSuccess)
        assertEquals(0, thirdReport.trackedPackagesCount)
        assertTrue(thirdReport.restoredPackages.isEmpty())
    }

    private class FakeRecoveryAdbManager(
        val installed: LinkedHashSet<String>,
        initialDisabled: LinkedHashSet<String> = linkedSetOf(),
        var currentHomePkg: String,
        var currentHomeAct: String
    ) : AdbConnectionManager {
        override val connectionState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
        override val pairingState = MutableStateFlow<AdbPairingState>(AdbPairingState.Idle)
        override val diagnosticCategory = MutableStateFlow(AdbDiagnosticCategory.NONE)
        override val discoveredServices = MutableStateFlow<List<DiscoveredAdbService>>(emptyList())
        override val isDiscovering = MutableStateFlow(false)
        override val savedEndpoint = MutableStateFlow<AdbEndpointConfig?>(null)
        override val lastDiagnosticDetail = MutableStateFlow("")
        override val pairingCapability = AdbPairingCapability(false, "")

        val disabled = linkedSetOf<String>().apply { addAll(initialDisabled) }
        val failEnablePackages = mutableSetOf<String>()
        var failSetHome: Boolean = false
        val setHomeCalls = mutableListOf<String>()
        val operationLog = mutableListOf<String>()

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
                sdkVersion = 28,
                buildId = "PI.3115",
                buildFingerprint = "Xiaomi/aquaman/aquaman:9/PI/3115:user/release-keys",
                currentHomePackage = currentHomePkg,
                currentHomeActivity = currentHomeAct,
                currentHomeLabel = currentHomePkg
            )
        )

        override suspend fun listAllPackages() = Result.success(installed.toList())
        override suspend fun listDisabledPackages() = Result.success(disabled.toSet())
        override suspend fun queryHomeActivities(): Result<List<String>> {
            val homes = linkedSetOf<String>()
            if (installed.contains(currentHomePkg) && !disabled.contains(currentHomePkg) && currentHomeAct.contains('/')) {
                homes.add(currentHomeAct)
            }
            if (installed.contains("com.google.android.tvlauncher") && !disabled.contains("com.google.android.tvlauncher") &&
                homes.none { it.substringBefore('/') == "com.google.android.tvlauncher" }
            ) {
                homes.add("com.google.android.tvlauncher/.MainActivity")
            }
            if (installed.contains(CleanYourTvConstants.LAUNCHER_PACKAGE) && !disabled.contains(CleanYourTvConstants.LAUNCHER_PACKAGE) &&
                homes.none { it.substringBefore('/') == CleanYourTvConstants.LAUNCHER_PACKAGE }
            ) {
                homes.add(CleanYourTvConstants.LAUNCHER_COMPONENT)
            }
            return Result.success(homes.toList())
        }

        override suspend fun resolveHomeActivityForPackage(packageName: String): Result<String?> =
            queryHomeActivities().map { list -> list.firstOrNull { it.substringBefore('/') == packageName } }

        override suspend fun disablePackageForUser(packageName: String): Result<AdbCommandResult> {
            operationLog.add("DISABLE:$packageName")
            disabled.add(packageName)
            return Result.success(AdbCommandResult(0, "disabled-user"))
        }

        override suspend fun enablePackage(packageName: String): Result<AdbCommandResult> {
            operationLog.add("ENABLE:$packageName")
            if (failEnablePackages.contains(packageName)) {
                return Result.failure(IOException("SecurityException: cannot enable $packageName"))
            }
            disabled.remove(packageName)
            return Result.success(AdbCommandResult(0, "enabled"))
        }

        override suspend fun launchHomeChooserOrOpenHome(): Result<AdbCommandResult> {
            operationLog.add("LAUNCH_HOME")
            return Result.success(AdbCommandResult(0, "OK"))
        }

        override suspend fun setDefaultHomeActivity(componentName: String): Result<AdbCommandResult> {
            setHomeCalls.add(componentName)
            operationLog.add("SET_HOME:$componentName")
            if (failSetHome) {
                return Result.failure(IOException("SecurityException: OEM firmware blocked HOME switch"))
            }
            currentHomeAct = componentName
            currentHomePkg = componentName.substringBefore('/')
            return Result.success(AdbCommandResult(0, "Success"))
        }

        override suspend fun restartCleanYourTvLauncher() = Result.success(AdbCommandResult(0, "OK"))

        override suspend fun installApk(apkBytes: ByteArray): Result<AdbCommandResult> {
            installed.add(CleanYourTvConstants.LAUNCHER_PACKAGE)
            return Result.success(AdbCommandResult(0, "Success"))
        }

        override suspend fun pushAppConfigFile(remotePath: String, content: ByteArray) = Result.success(AdbCommandResult(0, "OK"))
        override suspend fun readRemoteFile(remotePath: String) = Result.failure<ByteArray>(IOException())
        override suspend fun remoteFileExists(remotePath: String) = Result.success(false)
        override suspend fun ensureRemoteDirectory(remoteDir: String) = Result.success(AdbCommandResult(0, "OK"))
        override suspend fun removeRemoteFile(remotePath: String) = Result.success(AdbCommandResult(0, "OK"))
        override suspend fun checkFilePushSupported() = Result.success(true)
    }
}
