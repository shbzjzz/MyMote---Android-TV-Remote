package com.MyMote.tv.debloat

import android.content.Context
import android.content.SharedPreferences
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
import org.junit.Assert.assertNull
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
class DebloatManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("motetv_debloat_records", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("motetv_tvhome_recovery", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun stickDevice() = TvDeviceInfo(
        manufacturer = "Xiaomi",
        model = "MiTV-AESP0",
        deviceName = "aquaman",
        productBoard = "aquaman",
        androidVersion = "9",
        sdkVersion = 28,
        buildId = "PI.3115",
        buildFingerprint = "Xiaomi/aquaman/aquaman:9/PI/3115:user/release-keys",
        currentHomePackage = "com.google.android.tvlauncher",
        currentHomeActivity = "com.google.android.tvlauncher/.MainActivity",
        currentHomeLabel = "Android TV Home"
    )

    @Test
    fun backupFailureAbortsDebloatBeforeAnyDisableCommandRuns() = runTest {
        val adb = FakeDebloatAdbManager(
            deviceInfo = stickDevice(),
            allPackages = listOf("com.miui.tv.analytics", "com.mitv.tvhome.atv", "com.google.android.tvlauncher")
        )
        val failingRepo = DebloatRepository(context = null, simulateBackupFailureForTest = true)
        val scanner = TvPackageScanner(adb, failingRepo)
        val manager = DebloatManager(adb, scanner, failingRepo)

        assertTrue(manager.scanTv().isSuccess)
        val result = manager.disablePackagesWithBackup(listOf("com.miui.tv.analytics"))
        assertTrue("Disable must fail if mandatory backup fails", result.isFailure)
        assertTrue("No pm disable command may run if backup fails", adb.disableCalls.isEmpty())
    }

    @Test
    fun disablesInSmallVerifiedBatchesExcludesPreDisabledFromMoteTvTrackingAndRestoresAll() = runTest {
        val pkgs = listOf(
            "com.miui.tv.analytics",
            "com.xiaomi.statistic",
            "com.mitv.tvhome.atv",
            "com.mitv.tvhome.michannel",
            "com.mitv.tvhome.mitvplus",
            "com.xm.webcontent", // pre-disabled before MoteTV!
            "com.google.android.tvlauncher",
            "com.google.android.permissioncontroller"
        )
        val adb = FakeDebloatAdbManager(
            deviceInfo = stickDevice(),
            allPackages = pkgs,
            initialDisabled = setOf("com.xm.webcontent")
        )
        val repo = DebloatRepository(context = context)
        val scanner = TvPackageScanner(adb, repo)
        val manager = DebloatManager(adb, scanner, repo, timeProvider = { 1_700_000_000_000L })

        val scan = manager.scanTv().getOrThrow()
        assertTrue(scan.hasExactModelCatalogMatch)

        manager.togglePackageSelection("com.google.android.permissioncontroller")
        assertFalse(manager.selectedPackages.value.contains("com.google.android.permissioncontroller"))

        manager.selectRecommendedSafePackages()
        val selected = manager.selectedPackages.value
        assertEquals(5, selected.size)

        // Even if someone passes a pre-disabled package explicitly, it must NEVER be claimed as MoteTV-modified
        val batchRes = manager.disablePackagesWithBackup((selected + "com.xm.webcontent").sorted()).getOrThrow()
        assertEquals(6, batchRes.size)
        assertEquals(5, repo.getModifiedPackages().size)
        assertFalse("Pre-disabled package must NOT be tracked as modified by MoteTV", repo.getModifiedPackages().contains("com.xm.webcontent"))
        assertEquals(5, manager.modifiedPackages.value.size)

        val backup = manager.latestBackup.value
        assertNotNull(backup)
        assertEquals("com.google.android.tvlauncher", backup?.originalHomePackage)
        assertEquals(8, backup?.allInstalledPackages?.size)
        assertTrue(backup!!.originallyDisabledPackages.contains("com.xm.webcontent"))

        val exportJson = manager.exportBackupJson()!!
        assertTrue(exportJson.contains("com.google.android.tvlauncher"))
        assertTrue(exportJson.contains("operationHistory"))
        manager.markBackupExported("motetv_backup.json")

        // Restore all modified packages — pre-disabled com.xm.webcontent stays untouched!
        val restoreRes = manager.restoreAllModifiedByMoteTv().getOrThrow()
        assertEquals(5, restoreRes.size)
        assertTrue(restoreRes.all { it.succeeded })
        assertTrue(repo.getModifiedPackages().isEmpty())
        assertEquals(0, manager.modifiedPackages.value.size)
        assertFalse(adb.enableCalls.contains("com.xm.webcontent"))
    }

    @Test
    fun partialRestoreAttemptsAllPackagesAndReportsFailureHonestly() = runTest {
        val pkgs = listOf(
            "com.miui.tv.analytics",
            "com.xiaomi.statistic",
            "com.mitv.tvhome.atv",
            "com.google.android.tvlauncher"
        )
        val adb = FakeDebloatAdbManager(
            deviceInfo = stickDevice(),
            allPackages = pkgs,
            failEnableOnPackages = setOf("com.miui.tv.analytics")
        )
        val repo = DebloatRepository(context = context)
        val manager = DebloatManager(adb, TvPackageScanner(adb, repo), repo)

        assertTrue(manager.scanTv().isSuccess)
        manager.disablePackagesWithBackup(listOf("com.miui.tv.analytics", "com.xiaomi.statistic", "com.mitv.tvhome.atv")).getOrThrow()
        assertEquals(3, repo.getModifiedPackages().size)

        // Restore all: analytics fails to re-enable, but statistic and atv MUST still be attempted & restored!
        val res = manager.restoreAllModifiedByMoteTv()
        assertTrue("Partial restore failure must return Result.failure", res.isFailure)
        assertEquals(3, adb.enableCalls.size)
        // Verified enabled packages are unmarked; failed package stays tracked for retry!
        assertEquals(setOf("com.miui.tv.analytics"), repo.getModifiedPackages())
        assertEquals(setOf("com.miui.tv.analytics"), manager.modifiedPackages.value)
    }

    @Test
    fun stopsImmediatelyWhenBatchCommandOrVerificationFails() = runTest {
        val pkgs = listOf(
            "com.miui.tv.analytics",
            "com.xiaomi.statistic",
            "com.mitv.tvhome.atv",
            "com.mitv.tvhome.michannel"
        )
        val adb = FakeDebloatAdbManager(
            deviceInfo = stickDevice(),
            allPackages = pkgs,
            ignoreDisableOnPackages = setOf("com.xiaomi.statistic")
        )
        val repo = DebloatRepository(context = context)
        val manager = DebloatManager(adb, TvPackageScanner(adb, repo), repo)

        assertTrue(manager.scanTv().isSuccess)
        val res = manager.disablePackagesWithBackup(pkgs)
        assertTrue("Verification mismatch on batch 1 must fail and stop batch 2", res.isFailure)
        assertFalse(
            "Batch 2 package must not be attempted after batch 1 verification failure",
            adb.disableCalls.contains("com.mitv.tvhome.michannel")
        )
    }

    @Test
    fun failedInitialBackupCreationCommitReturnsFailureWithoutInMemoryFallbackOrDisabling() = runTest {
        val realPrefs = context.getSharedPreferences("motetv_debloat_records", Context.MODE_PRIVATE)
        val failingPrefs = FailingCommitSharedPreferences(realPrefs).apply {
            failBackupCommit = true
        }
        val adb = FakeDebloatAdbManager(
            deviceInfo = stickDevice(),
            allPackages = listOf("com.miui.tv.analytics", "com.xiaomi.statistic", "com.google.android.tvlauncher")
        )
        val repo = DebloatRepository(context = context, prefsOverride = failingPrefs)
        val manager = DebloatManager(adb, TvPackageScanner(adb, repo), repo, timeProvider = { 1_700_000_001_000L })

        val sampleBackup = DebloatBackupRecord(
            backupId = "init-fail-1",
            createdAtMs = 1_700_000_001_000L,
            deviceInfo = stickDevice(),
            allInstalledPackages = listOf("com.miui.tv.analytics", "com.google.android.tvlauncher"),
            originallyDisabledPackages = emptyList(),
            originalHomePackage = "com.google.android.tvlauncher",
            originalHomeActivity = "com.google.android.tvlauncher/.MainActivity",
            intendedDisablePackages = listOf("com.miui.tv.analytics"),
            modifiedByMoteTvPackages = emptyList()
        )
        val directSave = repo.saveBackup(sampleBackup)
        assertTrue("saveBackup must fail when commit() returns false", directSave.isFailure)
        assertNull("getLatestBackup must NEVER rely on in-memory data when commit fails", repo.getLatestBackup())

        assertTrue(manager.scanTv().isSuccess)
        val res = manager.disablePackagesWithBackup(listOf("com.miui.tv.analytics"))
        assertTrue("disablePackagesWithBackup must abort if backup cannot be durably persisted", res.isFailure)
        assertTrue("No package may be disabled if initial backup commit fails", adb.disableCalls.isEmpty())
        assertNull(manager.latestBackup.value)
        assertNull(repo.getLatestBackup())
    }

    @Test
    fun failedModifiedPackageTrackingCommitReturnsFailureAndStopsDebloat() = runTest {
        val realPrefs = context.getSharedPreferences("motetv_debloat_records", Context.MODE_PRIVATE)
        val failingPrefs = FailingCommitSharedPreferences(realPrefs).apply {
            failModifiedCommit = true
        }
        val adb = FakeDebloatAdbManager(
            deviceInfo = stickDevice(),
            allPackages = listOf(
                "com.miui.tv.analytics",
                "com.xiaomi.statistic",
                "com.mitv.tvhome.atv",
                "com.mitv.tvhome.michannel",
                "com.google.android.tvlauncher"
            )
        )
        val repo = DebloatRepository(context = context, prefsOverride = failingPrefs)
        val manager = DebloatManager(adb, TvPackageScanner(adb, repo), repo, timeProvider = { 1_700_000_002_000L })

        // Direct saveModifiedSet & markPackageModified must NOT silently report success on commit() == false
        val directSetRes = repo.saveModifiedSet(setOf("com.miui.tv.analytics"))
        assertTrue("saveModifiedSet must return failure when commit() returns false", directSetRes.isFailure)
        assertTrue(repo.markPackageModified("com.miui.tv.analytics").isFailure)
        assertTrue("Modified set must remain empty when commit fails", repo.getModifiedPackages().isEmpty())

        assertTrue(manager.scanTv().isSuccess)
        val batchRes = manager.disablePackagesWithBackup(
            listOf("com.miui.tv.analytics", "com.xiaomi.statistic", "com.mitv.tvhome.atv", "com.mitv.tvhome.michannel")
        )
        assertTrue("disablePackagesWithBackup must fail when modified-package persistence fails", batchRes.isFailure)
        assertFalse(
            "Batch 2 must NOT run after modified-package persistence failure in Batch 1",
            adb.disableCalls.contains("com.mitv.tvhome.michannel")
        )
        assertTrue(manager.modifiedPackages.value.isEmpty())
    }

    @Test
    fun failedBackupUpdatePreservesExistingDurableBackupWithoutInMemoryOverride() = runTest {
        val realPrefs = context.getSharedPreferences("motetv_debloat_records", Context.MODE_PRIVATE)
        val failingPrefs = FailingCommitSharedPreferences(realPrefs)
        val adb = FakeDebloatAdbManager(
            deviceInfo = stickDevice(),
            allPackages = listOf("com.miui.tv.analytics", "com.xiaomi.statistic", "com.google.android.tvlauncher")
        )
        val repo = DebloatRepository(context = context, prefsOverride = failingPrefs)
        var clock = 1_700_000_003_000L
        val manager = DebloatManager(adb, TvPackageScanner(adb, repo), repo, timeProvider = { clock })

        // Step 1: Initial backup & first package disable succeeds durably
        assertTrue(manager.scanTv().isSuccess)
        manager.disablePackagesWithBackup(listOf("com.miui.tv.analytics")).getOrThrow()
        val initialBackup = repo.getLatestBackup()!!
        assertEquals(listOf("com.miui.tv.analytics"), initialBackup.intendedDisablePackages)

        // Step 2: Simulate persistence failure on subsequent backup update
        failingPrefs.failBackupCommit = true
        clock += 5_000L

        val directUpdate = repo.saveBackup(
            initialBackup.copy(
                backupId = "failed-update-2",
                createdAtMs = clock,
                intendedDisablePackages = listOf("com.xiaomi.statistic"),
                modifiedByMoteTvPackages = listOf("com.miui.tv.analytics")
            )
        )
        assertTrue("Backup update must fail when commit() returns false", directUpdate.isFailure)
        assertEquals(
            "Existing durable backup must remain intact and NOT replaced by uncommitted in-memory data",
            initialBackup.backupId,
            repo.getLatestBackup()?.backupId
        )

        val secondRun = manager.disablePackagesWithBackup(listOf("com.xiaomi.statistic"))
        assertTrue("Debloat must refuse to proceed when backup update fails to persist", secondRun.isFailure)
        assertFalse("com.xiaomi.statistic must NOT be disabled when backup update failed", adb.disableCalls.contains("com.xiaomi.statistic"))
        assertEquals(initialBackup.backupId, manager.latestBackup.value?.backupId)
    }

    @Test
    fun recoveryAfterRestartWithFailedPersistenceUpdateUsesDurableRecordsAndReportsUnmarkFailure() = runTest {
        val realPrefs = context.getSharedPreferences("motetv_debloat_records", Context.MODE_PRIVATE)
        val controllablePrefs = FailingCommitSharedPreferences(realPrefs)
        val adb = FakeDebloatAdbManager(
            deviceInfo = stickDevice(),
            allPackages = listOf("com.miui.tv.analytics", "com.xiaomi.statistic", "com.google.android.tvlauncher")
        )

        // Session 1: Persist initial backup + modified package + original HOME cleanly
        val backupB1Id: String
        run {
            val repo1 = DebloatRepository(context = context, prefsOverride = controllablePrefs)
            val launcher1 = CleanYourTvLauncherManager(context, adb)
            launcher1.refreshLauncherStatus().getOrThrow()
            val mgr1 = DebloatManager(adb, TvPackageScanner(adb, repo1), repo1, timeProvider = { 1_700_000_004_000L })
            mgr1.scanTv().getOrThrow()
            mgr1.disablePackagesWithBackup(listOf("com.miui.tv.analytics")).getOrThrow()
            backupB1Id = repo1.getLatestBackup()!!.backupId

            // Subsequent update attempt fails persistence -> must not pollute durable state
            controllablePrefs.failBackupCommit = true
            assertTrue(mgr1.disablePackagesWithBackup(listOf("com.xiaomi.statistic")).isFailure)
        }

        // Session 2: Simulate app restart (new objects); persistence works except simulate modified-set unmark failure first
        controllablePrefs.failBackupCommit = false
        controllablePrefs.failModifiedCommit = true
        val repo2 = DebloatRepository(context = context, prefsOverride = controllablePrefs)
        val mgr2 = DebloatManager(adb, TvPackageScanner(adb, repo2), repo2)
        val launcher2 = CleanYourTvLauncherManager(context, adb)
        val coordinator2 = TvRecoveryCoordinator(mgr2, launcher2)

        assertEquals(backupB1Id, repo2.getLatestBackup()?.backupId)
        assertEquals(setOf("com.miui.tv.analytics"), mgr2.modifiedPackages.value)

        // If unmarkPackageModified commit() fails during restore, recovery MUST fail honestly rather than silently clearing state
        val failedPersistRecovery = coordinator2.recoverTv()
        assertTrue("Recovery must report failure when unmark commit() fails", failedPersistRecovery.isFailure)
        assertEquals(setOf("com.miui.tv.analytics"), repo2.getModifiedPackages())

        // Once persistence recovers, retry succeeds cleanly using durable records
        controllablePrefs.failModifiedCommit = false
        val recovered = coordinator2.recoverTv().getOrThrow()
        assertTrue(recovered.isCompleteSuccess)
        assertTrue(repo2.getModifiedPackages().isEmpty())
    }

    @Test
    fun pendingWalPersistenceFailureAbortsBeforeCallingAdbDisable() = runTest {
        val realPrefs = context.getSharedPreferences("motetv_debloat_records", Context.MODE_PRIVATE)
        val failingPrefs = FailingCommitSharedPreferences(realPrefs).apply {
            failPendingCommit = true
        }
        val adb = FakeDebloatAdbManager(
            deviceInfo = stickDevice(),
            allPackages = listOf("com.miui.tv.analytics", "com.google.android.tvlauncher")
        )
        val repo = DebloatRepository(context = context, prefsOverride = failingPrefs)
        val manager = DebloatManager(adb, TvPackageScanner(adb, repo), repo)

        assertTrue(manager.scanTv().isSuccess)
        val res = manager.disablePackagesWithBackup(listOf("com.miui.tv.analytics"))
        assertTrue("Disable must abort if WAL pending operation commit fails", res.isFailure)
        assertTrue("ADB disable must NEVER run if pending WAL was not persisted", adb.disableCalls.isEmpty())
    }

    @Test
    fun trackingPersistenceFailureRollsBackImmediatelyOrRetainsPendingRecordOnRollbackFailure() = runTest {
        // Case A: Tracking persistence fails -> immediate rollback re-enables TV package and halts remaining batch
        run {
            val realPrefs = context.getSharedPreferences("motetv_debloat_records", Context.MODE_PRIVATE)
            val failingPrefs = FailingCommitSharedPreferences(realPrefs).apply {
                failModifiedCommit = true
            }
            val adb = FakeDebloatAdbManager(
                deviceInfo = stickDevice(),
                allPackages = listOf("com.miui.tv.analytics", "com.xiaomi.statistic", "com.google.android.tvlauncher")
            )
            val repo = DebloatRepository(context = context, prefsOverride = failingPrefs)
            val manager = DebloatManager(adb, TvPackageScanner(adb, repo), repo)

            manager.scanTv().getOrThrow()
            val res = manager.disablePackagesWithBackup(listOf("com.miui.tv.analytics", "com.xiaomi.statistic"))
            assertTrue(res.isFailure)
            assertEquals(listOf("com.miui.tv.analytics"), adb.disableCalls)
            assertEquals(listOf("com.miui.tv.analytics"), adb.enableCalls)
            assertFalse(
                "Immediate rollback must restore package to enabled on TV",
                adb.listDisabledPackages().getOrThrow().contains("com.miui.tv.analytics")
            )
            assertFalse(
                "Second package must NOT be attempted after rollback",
                adb.disableCalls.contains("com.xiaomi.statistic")
            )
        }

        // Case B: Tracking persistence fails AND immediate rollback fails -> retains ROLLBACK_REQUIRED WAL & blocks new debloats
        context.getSharedPreferences("motetv_debloat_records", Context.MODE_PRIVATE).edit().clear().commit()
        run {
            val realPrefs = context.getSharedPreferences("motetv_debloat_records", Context.MODE_PRIVATE)
            val failingPrefs = FailingCommitSharedPreferences(realPrefs).apply {
                failModifiedCommit = true
            }
            val adb = FakeDebloatAdbManager(
                deviceInfo = stickDevice(),
                allPackages = listOf("com.miui.tv.analytics", "com.xiaomi.statistic", "com.google.android.tvlauncher"),
                failEnableOnPackages = setOf("com.miui.tv.analytics")
            )
            val repo = DebloatRepository(context = context, prefsOverride = failingPrefs)
            val manager = DebloatManager(adb, TvPackageScanner(adb, repo), repo)

            manager.scanTv().getOrThrow()
            val res = manager.disablePackagesWithBackup(listOf("com.miui.tv.analytics", "com.xiaomi.statistic"))
            assertTrue(res.isFailure)
            val msg = res.exceptionOrNull()?.message.orEmpty()
            assertTrue("Error must name affected package: $msg", msg.contains("com.miui.tv.analytics"))

            val pending = repo.getPendingOperations()
            assertEquals(1, pending.size)
            assertEquals("com.miui.tv.analytics", pending.first().packageName)
            assertEquals(PendingOperationStatus.ROLLBACK_REQUIRED, pending.first().status)

            // New debloat must be refused until unresolved ROLLBACK_REQUIRED is recovered
            failingPrefs.failModifiedCommit = false
            val blockedNewDebloat = manager.disablePackagesWithBackup(listOf("com.xiaomi.statistic"))
            assertTrue("Must block new debloat while ROLLBACK_REQUIRED WAL exists", blockedNewDebloat.isFailure)
            assertFalse(adb.disableCalls.contains("com.xiaomi.statistic"))
        }
    }

    @Test
    fun crashReconciliationHandlesPreAdbAndPostAdbInterruptionsWithoutClaimingPreDisabled() = runTest {
        val adb = FakeDebloatAdbManager(
            deviceInfo = stickDevice(),
            allPackages = listOf(
                "com.miui.tv.analytics",
                "com.xiaomi.statistic",
                "com.xm.webcontent",
                "com.google.android.tvlauncher"
            ),
            initialDisabled = setOf("com.xm.webcontent", "com.xiaomi.statistic") // statistic disabled after crash; webcontent pre-disabled!
        )
        val repo = DebloatRepository(context)
        // Simulate WAL entries left behind by abrupt crash:
        // 1. Pre-ADB crash for com.miui.tv.analytics (still enabled on TV)
        repo.upsertPendingOperation(
            PendingDebloatOperation(
                operationId = "op-pre-adb",
                backupId = "bk-1",
                packageName = "com.miui.tv.analytics",
                wasOriginallyDisabled = false,
                deviceManufacturer = "Xiaomi",
                deviceModel = "MiTV-AESP0",
                deviceFingerprint = "fp",
                createdAtMs = 100L
            )
        ).getOrThrow()
        // 2. Post-ADB, pre-tracking crash for com.xiaomi.statistic (disabled on TV, not pre-disabled)
        repo.upsertPendingOperation(
            PendingDebloatOperation(
                operationId = "op-post-adb",
                backupId = "bk-1",
                packageName = "com.xiaomi.statistic",
                wasOriginallyDisabled = false,
                deviceManufacturer = "Xiaomi",
                deviceModel = "MiTV-AESP0",
                deviceFingerprint = "fp",
                createdAtMs = 101L
            )
        ).getOrThrow()
        // 3. Pre-disabled package WAL entry (must NOT be claimed modified)
        repo.upsertPendingOperation(
            PendingDebloatOperation(
                operationId = "op-predisabled",
                backupId = "bk-1",
                packageName = "com.xm.webcontent",
                wasOriginallyDisabled = true,
                deviceManufacturer = "Xiaomi",
                deviceModel = "MiTV-AESP0",
                deviceFingerprint = "fp",
                createdAtMs = 102L
            )
        ).getOrThrow()

        val manager = DebloatManager(adb, TvPackageScanner(adb, repo), repo)
        val reconciled = manager.reconcilePendingOperations().getOrThrow()
        assertTrue(reconciled.isEmpty())
        assertEquals(setOf("com.xiaomi.statistic"), repo.getModifiedPackages())
        assertFalse(repo.getModifiedPackages().contains("com.miui.tv.analytics"))
        assertFalse(repo.getModifiedPackages().contains("com.xm.webcontent"))
    }

    @Test
    fun reconcilePendingOperationsContinuesAcrossAllEntriesEvenWhenEarlierEntryNeedsRollback() = runTest {
        val adb = FakeDebloatAdbManager(
            deviceInfo = stickDevice(),
            allPackages = listOf("com.miui.tv.analytics", "com.xiaomi.statistic", "com.google.android.tvlauncher"),
            initialDisabled = setOf("com.miui.tv.analytics", "com.xiaomi.statistic")
        )
        val repo = DebloatRepository(context)

        // Entry 1 is ROLLBACK_REQUIRED (still disabled on TV).
        repo.upsertPendingOperation(
            PendingDebloatOperation(
                operationId = "op-rb-1",
                backupId = "bk-multi",
                packageName = "com.miui.tv.analytics",
                wasOriginallyDisabled = false,
                deviceManufacturer = "Xiaomi",
                deviceModel = "MiTV-AESP0",
                deviceFingerprint = "fp",
                createdAtMs = 100L,
                status = PendingOperationStatus.ROLLBACK_REQUIRED,
                failureDetail = "Tracking persist failed"
            )
        ).getOrThrow()
        // Entry 2 is PENDING_DISABLE (also disabled on TV during crash).
        repo.upsertPendingOperation(
            PendingDebloatOperation(
                operationId = "op-pend-2",
                backupId = "bk-multi",
                packageName = "com.xiaomi.statistic",
                wasOriginallyDisabled = false,
                deviceManufacturer = "Xiaomi",
                deviceModel = "MiTV-AESP0",
                deviceFingerprint = "fp",
                createdAtMs = 101L,
                status = PendingOperationStatus.PENDING_DISABLE
            )
        ).getOrThrow()

        val manager = DebloatManager(adb, TvPackageScanner(adb, repo), repo)
        val res = manager.reconcilePendingOperations()
        assertTrue("Expected failure because op-rb-1 still needs rollback", res.isFailure)
        assertTrue(res.exceptionOrNull()?.message.orEmpty().contains("com.miui.tv.analytics"))
        assertTrue(
            "Subsequent WAL entry op-pend-2 must still be reconciled into modifiedPackages",
            repo.getModifiedPackages().contains("com.xiaomi.statistic")
        )
        assertEquals(
            "Only unresolved ROLLBACK_REQUIRED op-rb-1 should remain pending",
            listOf("com.miui.tv.analytics"),
            repo.getPendingOperations().map { it.packageName }
        )
    }

    @Test
    fun restorePackagesDetectsNonZeroExitCodeAndMissingInstalledPackage() = runTest {
        // Case A: enablePackage returns Result.success with non-zero exitCode -> must fail & keep tracking
        run {
            val adb = FakeDebloatAdbManager(
                deviceInfo = stickDevice(),
                allPackages = listOf("com.miui.tv.analytics", "com.xiaomi.statistic", "com.google.android.tvlauncher"),
                enableResultOverride = mapOf("com.miui.tv.analytics" to AdbCommandResult(1, "", "SecurityException: denied"))
            )
            val repo = DebloatRepository(context)
            val manager = DebloatManager(adb, TvPackageScanner(adb, repo), repo)

            manager.scanTv().getOrThrow()
            manager.disablePackagesWithBackup(listOf("com.miui.tv.analytics", "com.xiaomi.statistic")).getOrThrow()

            val restoreRes = manager.restoreAllModifiedByMoteTv()
            assertTrue("Non-zero pm enable exit code must fail restore", restoreRes.isFailure)
            assertEquals(setOf("com.miui.tv.analytics"), repo.getModifiedPackages())
        }

        // Case B: enablePackage returns exitCode 0 and pkg isn't in -d, but pkg is absent from pm list packages
        context.getSharedPreferences("motetv_debloat_records", Context.MODE_PRIVATE).edit().clear().commit()
        run {
            val adb = FakeDebloatAdbManager(
                deviceInfo = stickDevice(),
                allPackages = listOf("com.miui.tv.analytics", "com.google.android.tvlauncher")
            )
            val repo = DebloatRepository(context)
            val manager = DebloatManager(adb, TvPackageScanner(adb, repo), repo)

            manager.scanTv().getOrThrow()
            manager.disablePackagesWithBackup(listOf("com.miui.tv.analytics")).getOrThrow()

            // Remove package from installed list to simulate missing/uninstalled package during restore
            adb.mutableAllPackages.remove("com.miui.tv.analytics")
            val restoreRes = manager.restoreAllModifiedByMoteTv()
            assertTrue("Missing installed package must fail post-restore verification", restoreRes.isFailure)
            assertEquals(setOf("com.miui.tv.analytics"), repo.getModifiedPackages())
        }
    }

    @Test
    fun unverifiedSystemInventoryFailsClosedInDebloatManager() = runTest {
        val adb = FakeDebloatAdbManager(
            deviceInfo = stickDevice(),
            allPackages = listOf("com.google.android.tvlauncher", "com.indie.tvgame"),
            failListSystemPackages = true
        )
        val repo = DebloatRepository(context)
        val manager = DebloatManager(adb, TvPackageScanner(adb, repo), repo)

        val scan = manager.scanTv().getOrThrow()
        assertFalse(scan.hasExactModelCatalogMatch)

        val importRes = manager.importPackageListForReview(listOf("com.indie.tvgame"))
        assertTrue("Imported uncataloged app must be rejected when pm list packages -s fails", importRes.isFailure)
        assertTrue(importRes.exceptionOrNull() is SecurityException)

        val disableRes = manager.disablePackagesWithBackup(listOf("com.indie.tvgame"))
        assertTrue("Disable must fail closed when pm list packages -s fails", disableRes.isFailure)
        assertTrue(disableRes.exceptionOrNull() is SecurityException)
        assertTrue("No pm disable command may run when inventory unverified", adb.disableCalls.isEmpty())
    }

    private class FailingCommitSharedPreferences(
        private val delegate: SharedPreferences
    ) : SharedPreferences {
        var failBackupCommit: Boolean = false
        var failModifiedCommit: Boolean = false
        var failPendingCommit: Boolean = false

        override fun getAll(): MutableMap<String, *> = delegate.all
        override fun getString(key: String?, defValue: String?): String? = delegate.getString(key, defValue)
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            delegate.getStringSet(key, defValues)
        override fun getInt(key: String?, defValue: Int): Int = delegate.getInt(key, defValue)
        override fun getLong(key: String?, defValue: Long): Long = delegate.getLong(key, defValue)
        override fun getFloat(key: String?, defValue: Float): Float = delegate.getFloat(key, defValue)
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = delegate.getBoolean(key, defValue)
        override fun contains(key: String?): Boolean = delegate.contains(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
            delegate.registerOnSharedPreferenceChangeListener(listener)
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
            delegate.unregisterOnSharedPreferenceChangeListener(listener)

        override fun edit(): SharedPreferences.Editor {
            val delegateEditor = delegate.edit()
            val pendingKeys = mutableSetOf<String>()
            return object : SharedPreferences.Editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    key?.let(pendingKeys::add)
                    delegateEditor.putString(key, value)
                    return this
                }
                override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                    key?.let(pendingKeys::add)
                    delegateEditor.putStringSet(key, values)
                    return this
                }
                override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                    key?.let(pendingKeys::add)
                    delegateEditor.putInt(key, value)
                    return this
                }
                override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                    key?.let(pendingKeys::add)
                    delegateEditor.putLong(key, value)
                    return this
                }
                override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                    key?.let(pendingKeys::add)
                    delegateEditor.putFloat(key, value)
                    return this
                }
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                    key?.let(pendingKeys::add)
                    delegateEditor.putBoolean(key, value)
                    return this
                }
                override fun remove(key: String?): SharedPreferences.Editor {
                    key?.let(pendingKeys::add)
                    delegateEditor.remove(key)
                    return this
                }
                override fun clear(): SharedPreferences.Editor {
                    delegateEditor.clear()
                    return this
                }
                override fun commit(): Boolean {
                    if (failBackupCommit && pendingKeys.contains("latest_backup_json")) {
                        return false
                    }
                    if (failModifiedCommit && pendingKeys.contains("modified_by_motetv_set")) {
                        return false
                    }
                    if (failPendingCommit && pendingKeys.contains("pending_operations_json")) {
                        return false
                    }
                    return delegateEditor.commit()
                }
                override fun apply() {
                    commit()
                }
            }
        }
    }

    private class FakeDebloatAdbManager(
        private val deviceInfo: TvDeviceInfo,
        allPackages: List<String>,
        initialDisabled: Set<String> = emptySet(),
        private val ignoreDisableOnPackages: Set<String> = emptySet(),
        private val failEnableOnPackages: Set<String> = emptySet(),
        private val enableResultOverride: Map<String, AdbCommandResult> = emptyMap(),
        private val systemPackages: Set<String> = emptySet(),
        private val failListSystemPackages: Boolean = false
    ) : AdbConnectionManager {
        override val connectionState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
        override val pairingState = MutableStateFlow<AdbPairingState>(AdbPairingState.Idle)
        override val diagnosticCategory = MutableStateFlow(AdbDiagnosticCategory.NONE)
        override val discoveredServices = MutableStateFlow<List<DiscoveredAdbService>>(emptyList())
        override val isDiscovering = MutableStateFlow(false)
        override val savedEndpoint = MutableStateFlow<AdbEndpointConfig?>(null)
        override val lastDiagnosticDetail = MutableStateFlow("")
        override val pairingCapability = AdbPairingCapability(false, "")

        val mutableAllPackages = allPackages.toMutableList()
        val disableCalls = mutableListOf<String>()
        val enableCalls = mutableListOf<String>()
        private val disabledSet = linkedSetOf<String>().apply { addAll(initialDisabled) }

        override fun startWirelessDiscovery() {}
        override fun stopWirelessDiscovery() {}
        override fun connect(host: String, port: Int, mode: AdbConnectionMode) {}
        override fun disconnect() {}
        override suspend fun pairWirelessDebugging(host: String, pairingPort: Int, pairingCode: String) =
            Result.failure<String>(UnsupportedOperationException())
        override suspend fun inspectTvDevice() = Result.success(deviceInfo)
        override suspend fun listAllPackages() = Result.success(mutableAllPackages.toList())
        override suspend fun listDisabledPackages() = Result.success(disabledSet.toSet())
        override suspend fun listSystemPackages(): Result<Set<String>> =
            if (failListSystemPackages) Result.failure(IOException("pm list packages -s failed"))
            else Result.success(systemPackages)

        override suspend fun disablePackageForUser(packageName: String): Result<AdbCommandResult> {
            disableCalls.add(packageName)
            if (!ignoreDisableOnPackages.contains(packageName)) {
                disabledSet.add(packageName)
            }
            return Result.success(AdbCommandResult(0, "Package $packageName new state: disabled-user"))
        }

        override suspend fun enablePackage(packageName: String): Result<AdbCommandResult> {
            enableCalls.add(packageName)
            if (failEnableOnPackages.contains(packageName)) {
                return Result.failure(IOException("SecurityException: unable to enable $packageName"))
            }
            enableResultOverride[packageName]?.let { return Result.success(it) }
            disabledSet.remove(packageName)
            return Result.success(AdbCommandResult(0, "Package $packageName new state: enabled"))
        }

        override suspend fun launchHomeChooserOrOpenHome() = Result.success(AdbCommandResult(0, "OK"))
        override suspend fun queryHomeActivities(): Result<List<String>> {
            val homes = mutableListOf<String>()
            if (mutableAllPackages.contains("com.google.android.tvlauncher") &&
                !disabledSet.contains("com.google.android.tvlauncher")
            ) {
                homes.add("com.google.android.tvlauncher/.MainActivity")
            }
            val activeComponent = deviceInfo.currentHomeActivity
            if (mutableAllPackages.contains(deviceInfo.currentHomePackage) &&
                !disabledSet.contains(deviceInfo.currentHomePackage) &&
                !activeComponent.isNullOrBlank() &&
                activeComponent.contains('/') &&
                !homes.contains(activeComponent)
            ) {
                homes.add(activeComponent)
            }
            return Result.success(homes)
        }
        override suspend fun resolveHomeActivityForPackage(packageName: String): Result<String?> =
            queryHomeActivities().map { list -> list.firstOrNull { it.substringBefore('/') == packageName } }
        override suspend fun setDefaultHomeActivity(componentName: String) = Result.success(AdbCommandResult(0, "Success"))
        override suspend fun restartCleanYourTvLauncher() = Result.success(AdbCommandResult(0, "Restarted"))
        override suspend fun installApk(apkBytes: ByteArray) = Result.success(AdbCommandResult(0, "Success"))
        override suspend fun pushAppConfigFile(remotePath: String, content: ByteArray) = Result.success(AdbCommandResult(0, "OK"))
        override suspend fun readRemoteFile(remotePath: String) = Result.failure<ByteArray>(IOException())
        override suspend fun remoteFileExists(remotePath: String) = Result.success(false)
        override suspend fun ensureRemoteDirectory(remoteDir: String) = Result.success(AdbCommandResult(0, "OK"))
        override suspend fun removeRemoteFile(remotePath: String) = Result.success(AdbCommandResult(0, "OK"))
        override suspend fun checkFilePushSupported() = Result.success(true)
    }
}
