package com.MyMote.tv.debloat

import com.MyMote.tv.adb.AdbConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class DebloatManager(
    private val adbManager: AdbConnectionManager,
    private val scanner: TvPackageScanner,
    private val repository: DebloatRepository,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        const val BATCH_SIZE = 3
    }

    private val _scanResult = MutableStateFlow<TvScanResult?>(null)
    val scanResult: StateFlow<TvScanResult?> = _scanResult.asStateFlow()

    private val _selectedPackages = MutableStateFlow<Set<String>>(emptySet())
    val selectedPackages: StateFlow<Set<String>> = _selectedPackages.asStateFlow()

    private val _modifiedPackages = MutableStateFlow(repository.getModifiedPackages())
    val modifiedPackages: StateFlow<Set<String>> = _modifiedPackages.asStateFlow()

    private val _pendingOperations = MutableStateFlow(repository.getPendingOperations())
    val pendingOperations: StateFlow<List<PendingDebloatOperation>> = _pendingOperations.asStateFlow()

    private val _latestBackup = MutableStateFlow<DebloatBackupRecord?>(repository.getLatestBackup())
    val latestBackup: StateFlow<DebloatBackupRecord?> = _latestBackup.asStateFlow()

    private val _operationHistory = MutableStateFlow<List<DebloatOperationEntry>>(repository.getOperationHistory())
    val operationHistory: StateFlow<List<DebloatOperationEntry>> = _operationHistory.asStateFlow()

    private val _batchProgress = MutableStateFlow(BatchProgress(0, 0, null, false))
    val batchProgress: StateFlow<BatchProgress> = _batchProgress.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    suspend fun scanTv(): Result<TvScanResult> {
        _statusMessage.value = "Scanning TV packages (read-only)..."
        val res = scanner.scanTvPackages()
        res.onSuccess { scanned ->
            _scanResult.value = scanned
            _modifiedPackages.value = repository.getModifiedPackages()
            _pendingOperations.value = repository.getPendingOperations()
            _selectedPackages.value = _selectedPackages.value.filter { pkgName ->
                val norm = PackageSafetyGuard.normalizePackageName(pkgName)
                val entry = scanned.packages.firstOrNull {
                    PackageSafetyGuard.normalizePackageName(it.packageName) == norm
                }
                entry != null && !entry.isBlockedFromDisable
            }.toSet()
            _statusMessage.value = "Scanned ${scanned.packages.size} packages on ${scanned.deviceInfo.model}"
        }.onFailure {
            _statusMessage.value = "Scan failed: ${it.message}"
        }
        return res
    }

    fun togglePackageSelection(packageName: String) {
        val currentScan = _scanResult.value ?: return
        val normalized = PackageSafetyGuard.normalizePackageName(packageName)
        val pkg = currentScan.packages.firstOrNull {
            PackageSafetyGuard.normalizePackageName(it.packageName) == normalized
        } ?: return

        val installedSet = currentScan.packages.map { PackageSafetyGuard.normalizePackageName(it.packageName) }.toSet()
        val systemSet = currentScan.packages
            .filter { it.category == PackageCategory.SYSTEM_ESSENTIAL }
            .map { PackageSafetyGuard.normalizePackageName(it.packageName) }
            .toSet()
        val guardDecision = PackageSafetyGuard.evaluateDisableCandidate(
            packageName = pkg.packageName,
            deviceInfo = currentScan.deviceInfo,
            installedPackages = installedSet,
            verifiedReplacementHomeInstalled = false,
            verifiedReplacementHomeActive = false,
            systemPackages = systemSet
        )

        if (pkg.isBlockedFromDisable || !guardDecision.allowed) {
            _statusMessage.value = pkg.blockReason
                ?: guardDecision.reason
                ?: "Package $packageName is protected."
            return
        }
        val next = _selectedPackages.value.toMutableSet()
        if (next.contains(pkg.packageName)) {
            next.remove(pkg.packageName)
        } else {
            next.add(pkg.packageName)
        }
        _selectedPackages.value = next
    }

    fun selectRecommendedSafePackages() {
        val scan = _scanResult.value ?: return
        // Only auto-select SAFE_TO_DISABLE items when exact model match exists; NEVER auto-select unknown or optional items.
        if (!scan.hasExactModelCatalogMatch) {
            _statusMessage.value = "Exact verified model match unavailable for ${scan.deviceInfo.model}. Select packages individually."
            return
        }
        val installedSet = scan.packages.map { PackageSafetyGuard.normalizePackageName(it.packageName) }.toSet()
        val systemSet = scan.packages
            .filter { it.category == PackageCategory.SYSTEM_ESSENTIAL }
            .map { PackageSafetyGuard.normalizePackageName(it.packageName) }
            .toSet()
        val safeEnabled = scan.packages.filter { pkg ->
            val guardDecision = PackageSafetyGuard.evaluateDisableCandidate(
                packageName = pkg.packageName,
                deviceInfo = scan.deviceInfo,
                installedPackages = installedSet,
                verifiedReplacementHomeInstalled = false,
                verifiedReplacementHomeActive = false,
                systemPackages = systemSet
            )
            !pkg.isBlockedFromDisable &&
                guardDecision.allowed &&
                pkg.isEnabled &&
                pkg.recommendation == DebloatRecommendation.SAFE_TO_DISABLE &&
                !pkg.requiresOptionalPrompt &&
                !guardDecision.requiresExplicitOptionalPrompt &&
                pkg.category != PackageCategory.UNKNOWN_UNVERIFIED
        }.map { it.packageName }.toSet()
        _selectedPackages.value = safeEnabled
        _statusMessage.value = "Selected ${safeEnabled.size} verified low-risk packages for review."
    }

    /**
     * Validates an externally supplied / imported package list against PackageSafetyGuard.
     * Never allows any hard-blocked, stock launcher, or unknown system package into selection.
     */
    fun importPackageListForReview(packages: List<String>): Result<Set<String>> {
        val scan = _scanResult.value
            ?: return Result.failure(IllegalStateException("Scan TV packages before importing a selection list."))
        val installedSet = scan.packages.map { PackageSafetyGuard.normalizePackageName(it.packageName) }.toSet()
        val systemSet = scan.packages
            .filter { it.category == PackageCategory.SYSTEM_ESSENTIAL }
            .map { PackageSafetyGuard.normalizePackageName(it.packageName) }
            .toSet()

        val systemInventoryVerified = !scan.modelSafetyNotice.startsWith("System package inventory could not be verified")
        val byNormPkg = scan.packages.associateBy { PackageSafetyGuard.normalizePackageName(it.packageName) }

        val blockedReasons = mutableListOf<String>()
        val accepted = linkedSetOf<String>()

        for (rawPkg in packages) {
            val cleanPkg = rawPkg.trim()
            if (cleanPkg.isBlank()) continue
            val normPkg = PackageSafetyGuard.normalizePackageName(cleanPkg)
            val scannedEntry = byNormPkg[normPkg]
            val decision = PackageSafetyGuard.evaluateDisableCandidate(
                packageName = cleanPkg,
                deviceInfo = scan.deviceInfo,
                installedPackages = installedSet,
                verifiedReplacementHomeInstalled = false,
                verifiedReplacementHomeActive = false,
                systemPackages = systemSet,
                systemInventoryVerified = systemInventoryVerified
            )
            if (!decision.allowed || scannedEntry?.isBlockedFromDisable == true) {
                blockedReasons.add("$cleanPkg (${scannedEntry?.blockReason ?: decision.reason ?: "Protected package"})")
            } else if (installedSet.contains(normPkg)) {
                accepted.add(cleanPkg)
            }
        }

        if (blockedReasons.isNotEmpty()) {
            val msg = "Imported list rejected — blocked protected packages: ${blockedReasons.joinToString("; ")}"
            _statusMessage.value = msg
            return Result.failure(SecurityException(msg))
        }

        _selectedPackages.value = accepted
        _statusMessage.value = "Imported ${accepted.size} reviewable package(s)."
        return Result.success(accepted)
    }

    fun clearSelection() {
        _selectedPackages.value = emptySet()
    }

    /**
     * Reconciles durable crash-recovery WAL entries against actual TV state.
     * Ensures no interrupted disable operation is ever forgotten, never claims pre-disabled
     * packages as MoteTV-modified, and blocks new debloats if a rollback is still pending.
     */
    suspend fun reconcilePendingOperations(): Result<List<PendingDebloatOperation>> {
        val pending = repository.getPendingOperations()
        _pendingOperations.value = pending
        if (pending.isEmpty()) {
            return Result.success(emptyList())
        }

        val disabledOnTv = adbManager.listDisabledPackages().getOrElse { err ->
            val msg = "Cannot reconcile interrupted debloat operation against TV state: ${err.message}"
            _statusMessage.value = msg
            return Result.failure(IllegalStateException(msg, err))
        }.map { PackageSafetyGuard.normalizePackageName(it) }.toSet()

        val unresolvedRollbacks = mutableListOf<String>()
        for (op in pending) {
            val normPkg = PackageSafetyGuard.normalizePackageName(op.packageName)
            val isDisabledNow = disabledOnTv.contains(normPkg)

            if (op.wasOriginallyDisabled) {
                // Pre-disabled before MyMote: never claim as modified; just clear WAL entry.
                val removeRes = repository.removePendingOperation(op.packageName, op.operationId)
                if (removeRes.isFailure) {
                    _pendingOperations.value = repository.getPendingOperations()
                    return Result.failure(removeRes.exceptionOrNull()!!)
                }
                continue
            }

            if (op.status == PendingOperationStatus.ROLLBACK_REQUIRED) {
                if (!isDisabledNow) {
                    // Already re-enabled on TV; ensure tracking is clean and clear WAL entry.
                    val unmarkRes = repository.unmarkPackageModified(op.packageName)
                    _modifiedPackages.value = repository.getModifiedPackages()
                    if (unmarkRes.isFailure) {
                        return Result.failure(unmarkRes.exceptionOrNull()!!)
                    }
                    val removeRes = repository.removePendingOperation(op.packageName, op.operationId)
                    if (removeRes.isFailure) {
                        _pendingOperations.value = repository.getPendingOperations()
                        return Result.failure(removeRes.exceptionOrNull()!!)
                    }
                } else {
                    unresolvedRollbacks.add("${op.packageName} (${op.failureDetail ?: "interrupted operation"})")
                }
            } else {
                // PENDING_DISABLE interrupted by crash/disconnect
                if (isDisabledNow) {
                    // Package IS disabled on TV: durably record modification BEFORE clearing WAL!
                    val markRes = repository.markPackageModified(op.packageName)
                    _modifiedPackages.value = repository.getModifiedPackages()
                    if (markRes.isFailure) {
                        return Result.failure(
                            IllegalStateException(
                                "Crash reconciliation failed to record modified state for '${op.packageName}': ${markRes.exceptionOrNull()?.message}",
                                markRes.exceptionOrNull()
                            )
                        )
                    }
                }
                val removeRes = repository.removePendingOperation(op.packageName, op.operationId)
                if (removeRes.isFailure) {
                    _pendingOperations.value = repository.getPendingOperations()
                    return Result.failure(removeRes.exceptionOrNull()!!)
                }
            }
        }

        val remaining = repository.getPendingOperations()
        _pendingOperations.value = remaining
        if (unresolvedRollbacks.isNotEmpty()) {
            val msg = "Pending rollback required for ${unresolvedRollbacks.joinToString("; ")}. Run Recovery / Restore before continuing."
            _statusMessage.value = msg
            return Result.failure(IllegalStateException(msg))
        }
        return Result.success(remaining)
    }

    suspend fun createBackup(intendedTargets: List<String> = _selectedPackages.value.sorted()): Result<DebloatBackupRecord> {
        val devInfo = _scanResult.value?.deviceInfo
            ?: adbManager.inspectTvDevice().getOrElse { return Result.failure(it) }
        val allPkgs = adbManager.listAllPackages().getOrElse { return Result.failure(it) }
        val disabledPkgs = adbManager.listDisabledPackages().getOrElse { return Result.failure(it) }

        if (allPkgs.isEmpty()) {
            val err = IllegalStateException("TV package list is empty; refusing to create incomplete backup.")
            _statusMessage.value = "Backup failed — debloat blocked: ${err.message}"
            return Result.failure(err)
        }

        val record = DebloatBackupRecord(
            backupId = UUID.randomUUID().toString(),
            createdAtMs = timeProvider(),
            deviceInfo = devInfo,
            allInstalledPackages = allPkgs,
            originallyDisabledPackages = disabledPkgs.sorted(),
            originalHomePackage = devInfo.currentHomePackage,
            originalHomeActivity = devInfo.currentHomeActivity,
            intendedDisablePackages = intendedTargets,
            modifiedByMoteTvPackages = repository.getModifiedPackages().sorted()
        )
        val saved = repository.saveBackup(record)
        if (saved.isFailure) {
            val err = saved.exceptionOrNull()!!
            _latestBackup.value = repository.getLatestBackup()
            recordOperation(
                action = DebloatActionType.BACKUP_CREATED,
                packageName = null,
                succeeded = false,
                verifiedState = "Backup failed",
                detail = err.message ?: "Could not persist backup"
            )
            _statusMessage.value = "Backup failed — debloat blocked: ${err.message}"
            return Result.failure(err)
        }

        // Read-back verification ensures backup & required metadata are durably readable from persistent store
        val readBack = repository.getLatestBackup()
        if (readBack == null ||
            readBack.backupId != record.backupId ||
            readBack.createdAtMs != record.createdAtMs ||
            readBack.deviceInfo.manufacturer != record.deviceInfo.manufacturer ||
            readBack.deviceInfo.model != record.deviceInfo.model ||
            readBack.deviceInfo.buildFingerprint != record.deviceInfo.buildFingerprint ||
            readBack.allInstalledPackages.size != record.allInstalledPackages.size ||
            readBack.originallyDisabledPackages != record.originallyDisabledPackages ||
            readBack.originalHomePackage != record.originalHomePackage ||
            readBack.originalHomeActivity != record.originalHomeActivity ||
            readBack.intendedDisablePackages != record.intendedDisablePackages ||
            readBack.modifiedByMoteTvPackages != record.modifiedByMoteTvPackages
        ) {
            _latestBackup.value = readBack
            val err = IllegalStateException("Backup verification failed: saved record or required metadata could not be read back intact.")
            recordOperation(
                action = DebloatActionType.BACKUP_CREATED,
                packageName = null,
                succeeded = false,
                verifiedState = "Backup unreadable",
                detail = err.message!!
            )
            _statusMessage.value = "Backup verification failed — debloat blocked."
            return Result.failure(err)
        }

        _latestBackup.value = readBack
        recordOperation(
            action = DebloatActionType.BACKUP_CREATED,
            packageName = null,
            succeeded = true,
            verifiedState = "${allPkgs.size} pkgs (${disabledPkgs.size} pre-disabled)",
            detail = "Backup verified before changes (Original HOME: ${readBack.originalHomePackage ?: "unknown"})"
        )
        _statusMessage.value = "Backup saved & verified (${allPkgs.size} packages, HOME=${readBack.originalHomePackage ?: "unknown"})."
        return Result.success(readBack)
    }

    /**
     * Crash-safe, WAL-backed package disabling:
     * 1. Reconcile any interrupted pending operations from prior sessions.
     * 2. Validate every candidate against non-bypassable PackageSafetyGuard.
     * 3. Persist & read-back-verify full backup BEFORE any changes.
     * 4. For each package:
     *    - Persist & read-back-verify PendingDebloatOperation BEFORE ADB disable.
     *    - Execute ADB `pm disable-user --user 0`.
     *    - Verify actual TV state via `pm list packages -d`.
     *    - Persist `markPackageModified` (ONLY if `!wasOriginallyDisabled`).
     *      If persistence fails, immediately rollback (`pm enable`), verify re-enabled,
     *      retain recovery record if rollback/cleanup fails, and halt further packages.
     *    - Clear pending record ONLY after all persistence & verification checks succeed.
     */
    suspend fun disablePackagesWithBackup(
        packages: List<String>,
        verifiedReplacementHomeInstalled: Boolean = false,
        verifiedReplacementHomeActive: Boolean = false
    ): Result<List<DebloatOperationEntry>> {
        val cleanTargets = packages.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (cleanTargets.isEmpty()) {
            return Result.failure(IllegalArgumentException("No packages selected to disable."))
        }

        // Reconcile any prior interrupted WAL entries before starting new changes
        val reconcileRes = reconcilePendingOperations()
        if (reconcileRes.isFailure) {
            return Result.failure(reconcileRes.exceptionOrNull()!!)
        }

        val scan = _scanResult.value ?: scanTv().getOrElse { return Result.failure(it) }
        val installedSet = scan.packages.map { PackageSafetyGuard.normalizePackageName(it.packageName) }.toSet()
        val sysRes = adbManager.listSystemPackages()
        val systemInventoryVerified = sysRes.isSuccess
        val systemSet = sysRes.getOrElse { emptySet() }
            .map { PackageSafetyGuard.normalizePackageName(it) }
            .toSet()
        val thirdPartySet = adbManager.listThirdPartyPackages().getOrElse { emptySet() }
            .map { PackageSafetyGuard.normalizePackageName(it) }
            .toSet()

        // 1. Validate every candidate against non-bypassable safety guard (no override allowed)
        for (pkg in cleanTargets) {
            val decision = PackageSafetyGuard.evaluateDisableCandidate(
                packageName = pkg,
                deviceInfo = scan.deviceInfo,
                installedPackages = installedSet,
                verifiedReplacementHomeInstalled = verifiedReplacementHomeInstalled,
                verifiedReplacementHomeActive = verifiedReplacementHomeActive,
                systemPackages = systemSet,
                systemInventoryVerified = systemInventoryVerified,
                thirdPartyPackages = thirdPartySet
            )
            if (!decision.allowed) {
                val msg = decision.reason ?: "Blocked by safety guard: $pkg"
                _statusMessage.value = msg
                return Result.failure(SecurityException(msg))
            }
        }

        // 2. Mandatory backup (with read-back verification) BEFORE any modification
        val backup = createBackup(intendedTargets = cleanTargets).getOrElse { err ->
            return Result.failure(IllegalStateException("Mandatory backup failed; aborting debloat: ${err.message}", err))
        }
        val preExistingDisabled = backup.originallyDisabledPackages
            .map { PackageSafetyGuard.normalizePackageName(it) }
            .toSet()

        val batchResults = mutableListOf<DebloatOperationEntry>()
        _batchProgress.value = BatchProgress(cleanTargets.size, 0, cleanTargets.firstOrNull(), true)

        var processed = 0
        try {
            for (pkg in cleanTargets) {
                _batchProgress.value = BatchProgress(cleanTargets.size, processed, pkg, true)
                val normalizedPkg = PackageSafetyGuard.normalizePackageName(pkg)
                val wasPreDisabled = preExistingDisabled.contains(normalizedPkg)
                val operationId = UUID.randomUUID().toString()

                val pendingOp = PendingDebloatOperation(
                    operationId = operationId,
                    backupId = backup.backupId,
                    packageName = pkg,
                    wasOriginallyDisabled = wasPreDisabled,
                    deviceManufacturer = backup.deviceInfo.manufacturer,
                    deviceModel = backup.deviceInfo.model,
                    deviceFingerprint = backup.deviceInfo.buildFingerprint,
                    createdAtMs = timeProvider(),
                    status = PendingOperationStatus.PENDING_DISABLE
                )

                // Step 3a: Persist durable WAL record BEFORE executing ADB disable
                val walPersist = repository.upsertPendingOperation(pendingOp)
                _pendingOperations.value = repository.getPendingOperations()
                val walVerified = walPersist.isSuccess &&
                    repository.getPendingOperations().any { it.operationId == operationId && it.packageName == pkg }
                if (!walVerified) {
                    val err = walPersist.exceptionOrNull()
                        ?: IllegalStateException("Pending operation WAL read-back verification failed for $pkg")
                    val entry = recordOperation(
                        action = DebloatActionType.DISABLE_PACKAGE,
                        packageName = pkg,
                        succeeded = false,
                        verifiedState = "WAL_PERSIST_FAILED",
                        detail = "Aborted before ADB disable: ${err.message}"
                    )
                    batchResults.add(entry)
                    _statusMessage.value = "Aborted at $pkg: failed to persist pending WAL (${err.message})"
                    return Result.failure(err)
                }

                // Step 3b: Execute ADB disable command
                val cmdRes = adbManager.disablePackageForUser(pkg)
                if (cmdRes.isFailure) {
                    val err = cmdRes.exceptionOrNull()!!
                    // Check if TV actually disabled it despite command exception
                    val tvDisabledAfterErr = adbManager.listDisabledPackages().getOrNull()
                        ?.map { PackageSafetyGuard.normalizePackageName(it) }
                        ?.toSet()
                    if (tvDisabledAfterErr != null && !tvDisabledAfterErr.contains(normalizedPkg)) {
                        repository.removePendingOperation(pkg, operationId)
                        _pendingOperations.value = repository.getPendingOperations()
                    } else if (tvDisabledAfterErr != null && !wasPreDisabled && tvDisabledAfterErr.contains(normalizedPkg)) {
                        rollbackAfterPersistenceOrCommandFailure(pendingOp, err)
                    } else if (wasPreDisabled && tvDisabledAfterErr != null) {
                        repository.removePendingOperation(pkg, operationId)
                        _pendingOperations.value = repository.getPendingOperations()
                    }
                    val failEntry = recordOperation(
                        action = DebloatActionType.DISABLE_PACKAGE,
                        packageName = pkg,
                        succeeded = false,
                        verifiedState = "COMMAND_FAILED",
                        detail = "Stopped immediately at $pkg: ${err.message}"
                    )
                    batchResults.add(failEntry)
                    _statusMessage.value = "Stopped at $pkg: ${err.message}"
                    return Result.failure(err)
                }

                // Step 3c: Verify actual disabled state on TV via `pm list packages -d`
                val verifiedDisabledRes = adbManager.listDisabledPackages()
                if (verifiedDisabledRes.isFailure) {
                    val verifyErr = verifiedDisabledRes.exceptionOrNull()!!
                    val rollbackMsg = if (!wasPreDisabled) {
                        rollbackAfterPersistenceOrCommandFailure(pendingOp, verifyErr)
                    } else {
                        "Verification failed: ${verifyErr.message}"
                    }
                    val entry = recordOperation(
                        action = DebloatActionType.DISABLE_PACKAGE,
                        packageName = pkg,
                        succeeded = false,
                        verifiedState = "VERIFY_FAILED",
                        detail = rollbackMsg
                    )
                    batchResults.add(entry)
                    _statusMessage.value = "Batch verification failed on $pkg: $rollbackMsg"
                    return Result.failure(IllegalStateException(rollbackMsg, verifyErr))
                }

                val verifiedDisabled = verifiedDisabledRes.getOrThrow()
                    .map { PackageSafetyGuard.normalizePackageName(it) }
                    .toSet()
                if (!verifiedDisabled.contains(normalizedPkg)) {
                    repository.removePendingOperation(pkg, operationId)
                    _pendingOperations.value = repository.getPendingOperations()
                    val entry = recordOperation(
                        action = DebloatActionType.DISABLE_PACKAGE,
                        packageName = pkg,
                        succeeded = false,
                        verifiedState = "STILL_ENABLED",
                        detail = "Stopped: $pkg was not listed in pm list packages -d after disable"
                    )
                    batchResults.add(entry)
                    _statusMessage.value = "Verification mismatch on $pkg; stopping further packages."
                    return Result.failure(IllegalStateException("Verification failed: $pkg remained enabled"))
                }

                // Step 3d: Persist `markPackageModified` ONLY if `!wasPreDisabled`
                if (!wasPreDisabled) {
                    val markRes = repository.markPackageModified(pkg)
                    _modifiedPackages.value = repository.getModifiedPackages()
                    if (markRes.isFailure) {
                        val markErr = markRes.exceptionOrNull()!!
                        val detailMsg = rollbackAfterPersistenceOrCommandFailure(pendingOp, markErr)
                        val entry = recordOperation(
                            action = DebloatActionType.DISABLE_PACKAGE,
                            packageName = pkg,
                            succeeded = false,
                            verifiedState = "PERSIST_FAILED_ROLLED_BACK",
                            detail = detailMsg
                        )
                        batchResults.add(entry)
                        _statusMessage.value = detailMsg
                        return Result.failure(IllegalStateException(detailMsg, markErr))
                    }
                }

                // Step 3e: Clear pending WAL operation ONLY after all persistence checks succeeded
                val clearWalRes = repository.removePendingOperation(pkg, operationId)
                _pendingOperations.value = repository.getPendingOperations()
                if (clearWalRes.isFailure) {
                    val clearErr = clearWalRes.exceptionOrNull()!!
                    val entry = recordOperation(
                        action = DebloatActionType.DISABLE_PACKAGE,
                        packageName = pkg,
                        succeeded = false,
                        verifiedState = "WAL_CLEAR_FAILED",
                        detail = "Disabled $pkg and saved tracking, but failed to clear pending WAL: ${clearErr.message}"
                    )
                    batchResults.add(entry)
                    _statusMessage.value = entry.detail
                    return Result.failure(clearErr)
                }

                val note = if (wasPreDisabled) {
                    "Already disabled before MyMote (not claimed as MyMote change)"
                } else {
                    "Disabled via pm disable-user --user 0, verified in pm list packages -d, and durably persisted"
                }
                val entry = recordOperation(
                    action = DebloatActionType.DISABLE_PACKAGE,
                    packageName = pkg,
                    succeeded = true,
                    verifiedState = "DISABLED",
                    detail = note
                )
                batchResults.add(entry)
                processed++
            }

            _selectedPackages.value = emptySet()
            scanTv()
            _statusMessage.value = "Verified disabled ${batchResults.size} package(s). No auto-reboot performed."
            return Result.success(batchResults)
        } finally {
            _batchProgress.value = BatchProgress(cleanTargets.size, processed, null, false)
        }
    }

    private suspend fun rollbackAfterPersistenceOrCommandFailure(
        pendingOp: PendingDebloatOperation,
        triggerErr: Throwable
    ): String {
        val pkg = pendingOp.packageName
        val normalizedPkg = PackageSafetyGuard.normalizePackageName(pkg)
        val requiredRollback = pendingOp.copy(
            status = PendingOperationStatus.ROLLBACK_REQUIRED,
            failureDetail = triggerErr.message ?: "Persistence or verification failure"
        )
        repository.upsertPendingOperation(requiredRollback)
        _pendingOperations.value = repository.getPendingOperations()

        val enableRes = adbManager.enablePackage(pkg)
        if (enableRes.isFailure) {
            val enableErr = enableRes.exceptionOrNull()!!
            val retainMsg = "Tracking failed for $pkg (${triggerErr.message}) AND immediate rollback pm enable failed (${enableErr.message}). Pending recovery record retained."
            repository.upsertPendingOperation(requiredRollback.copy(failureDetail = retainMsg))
            _pendingOperations.value = repository.getPendingOperations()
            recordOperation(
                action = DebloatActionType.RESTORE_PACKAGE,
                packageName = pkg,
                succeeded = false,
                verifiedState = "ROLLBACK_FAILED",
                detail = retainMsg
            )
            return retainMsg
        }

        val disabledAfterRollback = adbManager.listDisabledPackages().getOrElse { verifyErr ->
            val retainMsg = "Tracking failed for $pkg (${triggerErr.message}); rollback ran but TV state verification failed (${verifyErr.message}). Pending recovery record retained."
            repository.upsertPendingOperation(requiredRollback.copy(failureDetail = retainMsg))
            _pendingOperations.value = repository.getPendingOperations()
            recordOperation(
                action = DebloatActionType.RESTORE_PACKAGE,
                packageName = pkg,
                succeeded = false,
                verifiedState = "ROLLBACK_VERIFY_FAILED",
                detail = retainMsg
            )
            return retainMsg
        }.map { PackageSafetyGuard.normalizePackageName(it) }.toSet()

        if (disabledAfterRollback.contains(normalizedPkg)) {
            val retainMsg = "Tracking failed for $pkg (${triggerErr.message}); rollback ran but $pkg remained disabled on TV. Pending recovery record retained."
            repository.upsertPendingOperation(requiredRollback.copy(failureDetail = retainMsg))
            _pendingOperations.value = repository.getPendingOperations()
            recordOperation(
                action = DebloatActionType.RESTORE_PACKAGE,
                packageName = pkg,
                succeeded = false,
                verifiedState = "STILL_DISABLED",
                detail = retainMsg
            )
            return retainMsg
        }

        // Rollback verified on TV -> clear WAL entry
        val clearRes = repository.removePendingOperation(pkg, pendingOp.operationId)
        _pendingOperations.value = repository.getPendingOperations()
        recordOperation(
            action = DebloatActionType.RESTORE_PACKAGE,
            packageName = pkg,
            succeeded = true,
            verifiedState = "ROLLED_BACK",
            detail = "Immediate rollback restored $pkg after persistence failure: ${triggerErr.message}"
        )
        return if (clearRes.isFailure) {
            "Modified-package persistence failed for $pkg (${triggerErr.message}); package was rolled back & verified enabled, but clearing pending record failed (${clearRes.exceptionOrNull()?.message})."
        } else {
            "Modified-package persistence failed for $pkg (${triggerErr.message}); immediately rolled back & verified enabled on TV. Batch halted."
        }
    }

    /**
     * Attempts to restore every package in [packages], continuing even if an individual package fails,
     * verifies final disabled state, unmarks ONLY verified enabled packages, clears matching WAL
     * recovery records once verified + persisted, and returns Result.failure whenever any package
     * could not be restored or persisted.
     */
    suspend fun restorePackages(packages: List<String>): Result<List<DebloatOperationEntry>> {
        if (packages.isEmpty()) {
            return Result.failure(IllegalArgumentException("No packages selected to restore."))
        }
        val results = mutableListOf<DebloatOperationEntry>()
        val commandErrors = linkedMapOf<String, String>()
        _batchProgress.value = BatchProgress(packages.size, 0, packages.firstOrNull(), true)
        var processed = 0
        try {
            for (pkg in packages) {
                _batchProgress.value = BatchProgress(packages.size, processed, pkg, true)
                val res = adbManager.enablePackage(pkg)
                if (res.isFailure) {
                    val err = res.exceptionOrNull()!!
                    commandErrors[pkg] = err.message ?: "pm enable failed"
                } else {
                    val cmd = res.getOrThrow()
                    if (!cmd.isSuccess) {
                        val detail = cmd.stderr.ifBlank { cmd.stdout }.ifBlank { "exit code ${cmd.exitCode}" }
                        commandErrors[pkg] = "pm enable failed: $detail"
                    }
                }
                processed++
            }

            val allInstalledAfter = adbManager.listAllPackages().getOrElse { err ->
                for (pkg in packages) {
                    val cmdErr = commandErrors[pkg]
                    results.add(
                        recordOperation(
                            action = DebloatActionType.RESTORE_PACKAGE,
                            packageName = pkg,
                            succeeded = false,
                            verifiedState = "VERIFY_FAILED",
                            detail = cmdErr ?: "Post-restore installed-package verification failed: ${err.message}"
                        )
                    )
                }
                _statusMessage.value = "Restore verification failed: ${err.message}"
                return Result.failure(err)
            }.map { PackageSafetyGuard.normalizePackageName(it) }.toSet()

            val stillDisabled = adbManager.listDisabledPackages().getOrElse { err ->
                for (pkg in packages) {
                    val cmdErr = commandErrors[pkg]
                    results.add(
                        recordOperation(
                            action = DebloatActionType.RESTORE_PACKAGE,
                            packageName = pkg,
                            succeeded = false,
                            verifiedState = "VERIFY_FAILED",
                            detail = cmdErr ?: "Post-restore verification failed: ${err.message}"
                        )
                    )
                }
                _statusMessage.value = "Restore verification failed: ${err.message}"
                return Result.failure(err)
            }.map { PackageSafetyGuard.normalizePackageName(it) }.toSet()

            val failedPackages = mutableListOf<String>()
            for (pkg in packages) {
                val cmdErr = commandErrors[pkg]
                val normalizedPkg = PackageSafetyGuard.normalizePackageName(pkg)
                val isInstalled = allInstalledAfter.contains(normalizedPkg)
                val restoredOnTv = cmdErr == null && isInstalled && !stillDisabled.contains(normalizedPkg)
                var persistErr: String? = null
                if (restoredOnTv) {
                    val unmarkRes = repository.unmarkPackageModified(pkg)
                    if (unmarkRes.isFailure) {
                        persistErr = "Package enabled on TV, but failed to persist tracking update: ${unmarkRes.exceptionOrNull()?.message}"
                        failedPackages.add(pkg)
                    } else {
                        val removeWalRes = repository.removePendingOperation(pkg)
                        if (removeWalRes.isFailure) {
                            persistErr = "Package enabled on TV, but failed to clear pending recovery record: ${removeWalRes.exceptionOrNull()?.message}"
                            failedPackages.add(pkg)
                        }
                    }
                } else {
                    failedPackages.add(pkg)
                }
                val succeeded = restoredOnTv && persistErr == null
                results.add(
                    recordOperation(
                        action = DebloatActionType.RESTORE_PACKAGE,
                        packageName = pkg,
                        succeeded = succeeded,
                        verifiedState = when {
                            cmdErr != null -> "ENABLE_FAILED"
                            !isInstalled -> "NOT_INSTALLED"
                            persistErr != null -> "PERSIST_FAILED"
                            restoredOnTv -> "ENABLED"
                            else -> "STILL_DISABLED"
                        },
                        detail = cmdErr ?: persistErr ?: when {
                            !isInstalled -> "Package not present in pm list packages after enable"
                            restoredOnTv -> "Restored via pm enable and verified"
                            else -> "Package still listed disabled after pm enable"
                        }
                    )
                )
            }

            _modifiedPackages.value = repository.getModifiedPackages()
            _pendingOperations.value = repository.getPendingOperations()
            scanTv()

            val succeededCount = results.count { it.succeeded }
            if (failedPackages.isNotEmpty()) {
                val msg = "Partial restore: restored $succeededCount/${packages.size}. Failed: ${failedPackages.joinToString()}"
                _statusMessage.value = msg
                return Result.failure(IllegalStateException(msg))
            }

            _statusMessage.value = "Restored & verified $succeededCount/${packages.size} package(s)."
            return Result.success(results)
        } finally {
            _batchProgress.value = BatchProgress(packages.size, processed, null, false)
        }
    }

    suspend fun collectRecoveryCandidatePackages(): Set<String> {
        val currentModified = repository.getModifiedPackages()
        val preDisabledBackup = repository.getLatestBackup()
            ?.originallyDisabledPackages
            ?.map { PackageSafetyGuard.normalizePackageName(it) }
            ?.toSet()
            ?: emptySet()

        val disabledOnTv = adbManager.listDisabledPackages().getOrNull()
            ?.map { PackageSafetyGuard.normalizePackageName(it) }
            ?.toSet()

        val activePendingTargets = linkedSetOf<String>()
        for (op in repository.getPendingOperations()) {
            val normPkg = PackageSafetyGuard.normalizePackageName(op.packageName)
            if (op.wasOriginallyDisabled || preDisabledBackup.contains(normPkg)) {
                // Pre-disabled before MoteTV: never restore; clear WAL record cleanly.
                repository.removePendingOperation(op.packageName, op.operationId)
                continue
            }
            if (disabledOnTv != null && !disabledOnTv.contains(normPkg)) {
                // Package is already enabled on TV (crash happened before ADB disabled it).
                if (op.status == PendingOperationStatus.PENDING_DISABLE ||
                    op.status == PendingOperationStatus.ROLLBACK_REQUIRED
                ) {
                    repository.removePendingOperation(op.packageName, op.operationId)
                    continue
                }
            }
            activePendingTargets.add(op.packageName)
        }
        _pendingOperations.value = repository.getPendingOperations()

        // Also reconcile interrupted intended targets from latest backup if disabled on TV & not pre-disabled
        val backupInterruptedTargets = if (disabledOnTv != null) {
            val latest = repository.getLatestBackup()
            latest?.intendedDisablePackages
                ?.filter { pkg ->
                    val norm = PackageSafetyGuard.normalizePackageName(pkg)
                    !preDisabledBackup.contains(norm) && disabledOnTv.contains(norm)
                }
                ?.toSet()
                ?: emptySet()
        } else {
            emptySet()
        }

        return (currentModified + activePendingTargets + backupInterruptedTargets).toSet()
    }

    suspend fun restoreAllModifiedByMoteTv(): Result<List<DebloatOperationEntry>> {
        val targets = collectRecoveryCandidatePackages().sorted()
        if (targets.isEmpty()) {
            _statusMessage.value = "No MyMote-modified packages require restoration."
            return Result.success(emptyList())
        }
        val res = restorePackages(targets)
        res.onSuccess { entries ->
            recordOperation(
                action = DebloatActionType.RESTORE_ALL,
                packageName = null,
                succeeded = true,
                verifiedState = "${entries.size} restored",
                detail = "Restored and verified all packages modified by MyMote"
            )
        }.onFailure { err ->
            recordOperation(
                action = DebloatActionType.RESTORE_ALL,
                packageName = null,
                succeeded = false,
                verifiedState = "PARTIAL_OR_FAILED",
                detail = err.message ?: "Restore all encountered failures"
            )
        }
        return res
    }

    fun exportBackupJson(): String? = repository.exportLatestBackupJson()

    fun markBackupExported(targetLabel: String) {
        recordOperation(
            action = DebloatActionType.BACKUP_EXPORTED,
            packageName = null,
            succeeded = true,
            verifiedState = "EXPORTED",
            detail = "Backup exported via Android SAF ($targetLabel)"
        )
    }

    private fun recordOperation(
        action: DebloatActionType,
        packageName: String?,
        succeeded: Boolean,
        verifiedState: String,
        detail: String
    ): DebloatOperationEntry {
        val entry = DebloatOperationEntry(
            id = UUID.randomUUID().toString(),
            timestampMs = timeProvider(),
            action = action,
            packageName = packageName,
            succeeded = succeeded,
            verifiedState = verifiedState,
            detail = detail
        )
        repository.appendHistory(entry)
        _operationHistory.value = repository.getOperationHistory()
        return entry
    }
}
