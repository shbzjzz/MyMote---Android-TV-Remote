package com.MyMote.tv.debloat

import com.MyMote.tv.tvhome.CleanYourTvConstants
import com.MyMote.tv.tvhome.CleanYourTvLauncherManager

data class TvRecoveryReport(
    val trackedPackagesCount: Int,
    val restoredPackagesCount: Int,
    val failedPackages: List<String>,
    val packageRestoreError: String?,
    val homeRestoreAttempted: Boolean,
    val originalHomePackage: String?,
    val homeRestored: Boolean,
    val verifiedActiveHomePackage: String?,
    val homeRestoreError: String?,
    val restoredPackages: List<String> = emptyList(),
    val pendingPackages: List<String> = emptyList(),
    val unverifiedPackages: List<String> = emptyList()
) {
    val isCompleteSuccess: Boolean
        get() = failedPackages.isEmpty() &&
            unverifiedPackages.isEmpty() &&
            pendingPackages.isEmpty() &&
            packageRestoreError == null &&
            (!homeRestoreAttempted || homeRestored) &&
            homeRestoreError == null

    fun formatSummary(): String {
        val pkgSummary = when {
            trackedPackagesCount == 0 && failedPackages.isEmpty() && unverifiedPackages.isEmpty() && packageRestoreError == null ->
                "Packages: none tracked for restoration"
            failedPackages.isEmpty() && unverifiedPackages.isEmpty() && packageRestoreError == null ->
                "Packages: restored $restoredPackagesCount/$trackedPackagesCount"
            else -> buildString {
                append("Packages: restored $restoredPackagesCount/$trackedPackagesCount")
                if (failedPackages.isNotEmpty()) {
                    append("; failed=${failedPackages.joinToString()}")
                }
                if (pendingPackages.isNotEmpty()) {
                    append("; pending=${pendingPackages.joinToString()}")
                }
                if (unverifiedPackages.isNotEmpty()) {
                    append("; unverified=${unverifiedPackages.joinToString()}")
                }
                if (failedPackages.isEmpty() && unverifiedPackages.isEmpty() && !packageRestoreError.isNullOrBlank()) {
                    append("; error=$packageRestoreError")
                }
            }
        }
        val homeSummary = when {
            homeRestored ->
                "HOME: verified '$verifiedActiveHomePackage' active"
            homeRestoreAttempted && homeRestoreError != null ->
                "HOME restore FAILED (${originalHomePackage ?: "unknown"}): $homeRestoreError"
            homeRestoreAttempted ->
                "HOME restore incomplete (active='${verifiedActiveHomePackage ?: "unknown"}')"
            originalHomePackage == null && verifiedActiveHomePackage == CleanYourTvConstants.LAUNCHER_PACKAGE ->
                "HOME: no saved original HOME record exists while CleanYourTV is active"
            else ->
                "HOME: no saved HOME restoration needed"
        }
        return if (isCompleteSuccess) {
            "Recovery complete — $pkgSummary. $homeSummary."
        } else {
            "Incomplete TV recovery — $pkgSummary. $homeSummary."
        }
    }
}

class TvRecoveryException(
    val report: TvRecoveryReport,
    message: String = report.formatSummary(),
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/**
 * Coordinates durable TV recovery across app restarts by reading persisted package-modification
 * records and persisted original HOME launcher metadata, preserving partial recovery progress,
 * enabling and verifying original HOME restoration, and never masking partial, unverified, or HOME failures.
 */
class TvRecoveryCoordinator(
    private val debloatManager: DebloatManager,
    private val launcherManager: CleanYourTvLauncherManager
) {

    fun resolvePersistedOriginalHomePackage(): String? {
        val (launcherPkg, _) = launcherManager.getPersistedOriginalHome()
        if (!launcherPkg.isNullOrBlank() && launcherPkg != CleanYourTvConstants.LAUNCHER_PACKAGE) {
            return launcherPkg
        }
        val backup = debloatManager.latestBackup.value
        val backupPkg = backup?.originalHomePackage
        val backupAct = backup?.originalHomeActivity
        if (!backupPkg.isNullOrBlank() &&
            backupPkg != CleanYourTvConstants.LAUNCHER_PACKAGE &&
            CleanYourTvLauncherManager.validateOriginalHomeComponent(backupPkg, backupAct).first
        ) {
            return backupPkg
        }
        return null
    }

    suspend fun recoverTv(): Result<TvRecoveryReport> {
        val trackedBefore = debloatManager.collectRecoveryCandidatePackages()
        val trackedCount = trackedBefore.size

        // 1. Restore all MyMote-modified and crash-interrupted packages (retains partial progress & WAL records on failure)
        val pkgRes = debloatManager.restoreAllModifiedByMoteTv()
        val remainingPending = debloatManager.pendingOperations.value
            .filter { !it.wasOriginallyDisabled }
            .map { it.packageName }
            .toSet()
        val remainingAfter = debloatManager.modifiedPackages.value + remainingPending
        val pkgError = pkgRes.exceptionOrNull()?.message

        val latestHistory = debloatManager.operationHistory.value
        val verificationUnavailable = pkgRes.isFailure && trackedCount > 0 && latestHistory
            .take(trackedCount)
            .any { it.action == DebloatActionType.RESTORE_PACKAGE && it.verifiedState == "VERIFY_FAILED" }

        val unverifiedPkgs: List<String>
        val failedPkgs: List<String>
        val restoredPkgs: List<String>
        if (verificationUnavailable) {
            unverifiedPkgs = trackedBefore.sorted()
            failedPkgs = trackedBefore.sorted()
            restoredPkgs = emptyList()
        } else {
            unverifiedPkgs = emptyList()
            failedPkgs = remainingAfter.intersect(trackedBefore).sorted()
            restoredPkgs = (trackedBefore - failedPkgs.toSet()).sorted()
        }
        val restoredCount = restoredPkgs.size
        val pendingPkgs = (remainingPending + unverifiedPkgs).distinct().sorted()

        // 2. Retrieve verified persisted original HOME record from CleanYourTvLauncherManager prefs or verified Debloat backup
        val (savedLauncherPkg, savedLauncherAct) = launcherManager.getPersistedOriginalHome()
        val backup = debloatManager.latestBackup.value
        val validPersistedPkg = when {
            !savedLauncherPkg.isNullOrBlank() &&
                !savedLauncherAct.isNullOrBlank() &&
                CleanYourTvLauncherManager.validateOriginalHomeComponent(savedLauncherPkg, savedLauncherAct).first ->
                savedLauncherPkg
            backup != null &&
                CleanYourTvLauncherManager.validateOriginalHomeComponent(backup.originalHomePackage, backup.originalHomeActivity).first &&
                launcherManager.seedOriginalHomeIfMissing(backup.originalHomePackage, backup.originalHomeActivity) ->
                backup.originalHomePackage
            else -> null
        }

        // Refresh launcher status from TV before deciding HOME recovery steps
        val refreshedStatus = launcherManager.refreshLauncherStatus().getOrNull()
        val activeHomeBeforeRestore = refreshedStatus?.currentHomePackage
            ?: launcherManager.launcherStatus.value.currentHomePackage

        // Restore original HOME ONLY when a valid persisted original launcher record exists
        var homeAttempted = false
        var homeRestored = false
        var verifiedHomePkg = activeHomeBeforeRestore
        var homeError: String? = null

        if (!validPersistedPkg.isNullOrBlank()) {
            homeAttempted = true
            val homeRes = launcherManager.restoreOriginalHomeLauncher()
            homeRes.onSuccess { verifiedStatus ->
                homeRestored = verifiedStatus.currentHomePackage == validPersistedPkg
                verifiedHomePkg = verifiedStatus.currentHomePackage
                if (!homeRestored) {
                    homeError = "Expected active HOME '$validPersistedPkg', got '${verifiedStatus.currentHomePackage}'"
                }
            }.onFailure { err ->
                homeRestored = false
                verifiedHomePkg = launcherManager.launcherStatus.value.currentHomePackage ?: verifiedHomePkg
                homeError = err.message ?: "HOME restoration failed"
            }
        } else if (activeHomeBeforeRestore == CleanYourTvConstants.LAUNCHER_PACKAGE) {
            homeError = "No verified original HOME record exists in persistent storage."
        }

        val report = TvRecoveryReport(
            trackedPackagesCount = trackedCount,
            restoredPackagesCount = restoredCount,
            failedPackages = failedPkgs,
            packageRestoreError = pkgError,
            homeRestoreAttempted = homeAttempted,
            originalHomePackage = validPersistedPkg,
            homeRestored = homeRestored,
            verifiedActiveHomePackage = verifiedHomePkg,
            homeRestoreError = homeError,
            restoredPackages = restoredPkgs,
            pendingPackages = pendingPkgs,
            unverifiedPackages = unverifiedPkgs
        )

        return if (report.isCompleteSuccess) {
            Result.success(report)
        } else {
            val cause = pkgRes.exceptionOrNull()
            Result.failure(TvRecoveryException(report, report.formatSummary(), cause))
        }
    }
}
