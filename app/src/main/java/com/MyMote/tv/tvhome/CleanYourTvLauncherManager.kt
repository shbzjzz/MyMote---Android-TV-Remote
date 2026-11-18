package com.MyMote.tv.tvhome

import android.content.Context
import android.content.SharedPreferences
import com.MyMote.tv.adb.AdbConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages CleanYourTV Home (`com.gathin.tvhome`) compatibility checks, APK installation,
 * default HOME switching, verification, and reversible stock HOME restoration.
 *
 * Fail-closed guarantees:
 * - Original HOME package AND activity component MUST be durably committed and read-back verified
 *   (with explicit `orig_home_valid = true`) in SharedPreferences BEFORE any HOME switch runs.
 * - Never falls back to in-memory storage when persistent storage is unavailable or fails commit.
 * - Never guesses a synthetic MainActivity component on restore; discovers HOME-capable components via PackageManager.
 * - Never erases the persisted original HOME backup when initiating or failing restoration.
 */
class CleanYourTvLauncherManager(
    context: Context?,
    private val adbManager: AdbConnectionManager,
    private val simulatePersistenceFailureForTest: Boolean = false,
    private val prefsOverride: SharedPreferences? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        private const val PREFS_NAME = "motetv_tvhome_recovery"
        private const val KEY_ORIG_HOME_PKG = "orig_home_pkg"
        private const val KEY_ORIG_HOME_ACT = "orig_home_act"
        private const val KEY_ORIG_HOME_VALID = "orig_home_valid"
        private const val KEY_ORIG_HOME_CAPTURED_AT = "orig_home_captured_at"
        private const val KEY_ORIG_HOME_LAST_RESTORED_AT = "orig_home_last_restored_at"

        private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9._]+$")
        private val COMPONENT_NAME_REGEX = Regex("^[a-zA-Z0-9._/$]+$")

        fun manualSwitchToCleanYourTvInstructions(): String =
            "Manual step on TV: open TV Settings > Apps > See all apps > Default apps (or Special app access) > Home app and select CleanYourTV Home, or press HOME and choose 'Always'."

        fun manualRestoreOriginalHomeInstructions(originalPkg: String?): String {
            val targetLabel = originalPkg?.takeIf { it.isNotBlank() } ?: "your original TV launcher"
            return "Manual step on TV: open TV Settings > Apps > Default apps > Home app and select '$targetLabel' (safe fallback: rebooting or uninstalling CleanYourTV returns HOME picker)."
        }

        fun validateOriginalHomeComponent(pkg: String?, act: String?): Pair<Boolean, String?> {
            val cleanPkg = pkg?.trim().orEmpty()
            val cleanAct = act?.trim().orEmpty()
            if (cleanPkg.isBlank() || !PACKAGE_NAME_REGEX.matches(cleanPkg)) {
                return false to "Original HOME package name is blank or invalid ('$pkg')."
            }
            if (cleanPkg == CleanYourTvConstants.LAUNCHER_PACKAGE) {
                return false to "Refusing to treat replacement launcher '${CleanYourTvConstants.LAUNCHER_PACKAGE}' as original HOME."
            }
            if (cleanAct.isBlank() || !cleanAct.contains('/') || !COMPONENT_NAME_REGEX.matches(cleanAct)) {
                return false to "Original HOME activity component is missing or malformed ('$act')."
            }
            val actPkg = cleanAct.substringBefore('/')
            val actCls = cleanAct.substringAfter('/')
            if (actPkg != cleanPkg || actCls.isBlank()) {
                return false to "Original HOME component '$cleanAct' does not match package '$cleanPkg'."
            }
            return true to null
        }

        private fun normalizeComponent(component: String): String {
            val trimmed = component.trim()
            val pkg = trimmed.substringBefore('/')
            val cls = trimmed.substringAfter('/', "")
            val expandedCls = if (cls.startsWith(".")) "$pkg$cls" else cls
            return "$pkg/$expandedCls"
        }

        private fun componentsMatch(a: String, b: String): Boolean =
            normalizeComponent(a) == normalizeComponent(b)
    }

    private val prefs: SharedPreferences? by lazy {
        prefsOverride ?: context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _launcherStatus = MutableStateFlow(CleanYourTvLauncherStatus())
    val launcherStatus: StateFlow<CleanYourTvLauncherStatus> = _launcherStatus.asStateFlow()

    init {
        val (savedPkg, savedAct) = loadOriginalHome()
        if (!savedPkg.isNullOrBlank()) {
            _launcherStatus.value = _launcherStatus.value.copy(
                originalHomePackage = savedPkg,
                originalHomeActivity = savedAct
            )
        }
    }

    fun getPersistedOriginalHome(): Pair<String?, String?> = loadOriginalHome()

    /**
     * Seeds the persisted original HOME record from an external verified backup (e.g. DebloatBackupRecord)
     * ONLY if both package & activity pass validation and durable commit + read-back succeed.
     */
    fun seedOriginalHomeIfMissing(pkg: String?, activity: String?): Boolean {
        val cleanPkg = pkg?.trim()?.ifBlank { null } ?: return false
        val cleanAct = activity?.trim()?.ifBlank { null } ?: return false
        if (!validateOriginalHomeComponent(cleanPkg, cleanAct).first) return false
        val (existingPkg, existingAct) = loadOriginalHome()
        if (existingPkg.isNullOrBlank() || existingAct.isNullOrBlank()) {
            val saved = saveOriginalHome(cleanPkg, cleanAct)
            if (saved.isFailure) return false
            _launcherStatus.value = _launcherStatus.value.copy(
                originalHomePackage = cleanPkg,
                originalHomeActivity = cleanAct
            )
        }
        return true
    }

    suspend fun refreshLauncherStatus(): Result<CleanYourTvLauncherStatus> {
        val devInfo = adbManager.inspectTvDevice().getOrElse { err ->
            val (savedOrigPkg, savedOrigAct) = loadOriginalHome()
            _launcherStatus.value = _launcherStatus.value.copy(
                originalHomePackage = savedOrigPkg,
                originalHomeActivity = savedOrigAct,
                statusDetail = "Unable to inspect TV HOME launcher: ${err.message}"
            )
            return Result.failure(err)
        }
        val allPackages = adbManager.listAllPackages().getOrElse { return Result.failure(it) }
        val disabled = adbManager.listDisabledPackages().getOrElse { emptySet() }
        val filePushOk = adbManager.checkFilePushSupported().getOrDefault(false)

        val currentPkg = devInfo.currentHomePackage?.trim()?.ifBlank { null }
        var currentAct = devInfo.currentHomeActivity?.trim()?.ifBlank { null }
        if (!currentPkg.isNullOrBlank() && currentPkg != CleanYourTvConstants.LAUNCHER_PACKAGE) {
            val (actValid, _) = validateOriginalHomeComponent(currentPkg, currentAct)
            if (!actValid) {
                currentAct = adbManager.resolveHomeActivityForPackage(currentPkg).getOrNull()
            }
            if (validateOriginalHomeComponent(currentPkg, currentAct).first) {
                // Best-effort opportunistic capture during passive refresh; activate/switch enforces strictly.
                saveOriginalHome(currentPkg, currentAct!!)
            }
        }

        val cleanInstalled = allPackages.contains(CleanYourTvConstants.LAUNCHER_PACKAGE) &&
            !disabled.contains(CleanYourTvConstants.LAUNCHER_PACKAGE)
        val cleanActive = currentPkg == CleanYourTvConstants.LAUNCHER_PACKAGE

        val (savedOrigPkg, savedOrigAct) = loadOriginalHome()
        val origAvailable = savedOrigPkg == null || (allPackages.contains(savedOrigPkg) && !disabled.contains(savedOrigPkg))
        val sdkCompatible = devInfo.sdkVersion >= CleanYourTvConstants.MIN_SUPPORTED_SDK || devInfo.sdkVersion == 0

        val status = CleanYourTvLauncherStatus(
            isSdkCompatible = sdkCompatible,
            tvSdkVersion = devInfo.sdkVersion,
            isSideloadingSupported = sdkCompatible,
            isCleanYourTvInstalled = cleanInstalled,
            isCleanYourTvActiveHome = cleanActive,
            currentHomePackage = currentPkg,
            currentHomeActivity = currentAct,
            originalHomePackage = savedOrigPkg,
            originalHomeActivity = savedOrigAct,
            isOriginalHomeAvailable = origAvailable,
            filePushSupported = filePushOk,
            statusDetail = buildStatusSummary(devInfo.sdkVersion, cleanInstalled, cleanActive, currentPkg, filePushOk)
        )
        _launcherStatus.value = status
        return Result.success(status)
    }

    suspend fun installCleanYourTvApk(apkBytes: ByteArray): Result<CleanYourTvLauncherStatus> {
        val current = refreshLauncherStatus().getOrElse { return Result.failure(it) }
        if (!current.isSdkCompatible) {
            return Result.failure(UnsupportedOperationException("TV Android SDK ${current.tvSdkVersion} is below minimum supported SDK ${CleanYourTvConstants.MIN_SUPPORTED_SDK}."))
        }
        val installRes = adbManager.installApk(apkBytes)
        if (installRes.isFailure) {
            return Result.failure(installRes.exceptionOrNull()!!)
        }
        val refreshed = refreshLauncherStatus().getOrElse { return Result.failure(it) }
        return if (refreshed.isCleanYourTvInstalled) {
            Result.success(refreshed)
        } else {
            Result.failure(IllegalStateException("APK install completed, but ${CleanYourTvConstants.LAUNCHER_PACKAGE} was not found in installed packages."))
        }
    }

    suspend fun activateCleanYourTvAsHome(): Result<CleanYourTvLauncherStatus> {
        val preflight = refreshLauncherStatus().getOrElse { return Result.failure(it) }
        if (!preflight.isCleanYourTvInstalled) {
            return Result.failure(IllegalStateException("CleanYourTV Home (${CleanYourTvConstants.LAUNCHER_PACKAGE}) is not installed or is disabled on the TV."))
        }

        // Validate target launcher exposes a real HOME-capable activity BEFORE touching TV settings.
        val queriedHomes = adbManager.queryHomeActivities().getOrElse { err ->
            val msg = "Aborted HOME switch: unable to verify HOME activities on TV (${err.message})."
            _launcherStatus.value = preflight.copy(statusDetail = msg)
            return Result.failure(IllegalStateException(msg, err))
        }
        val cleanHomeCandidates = queriedHomes.filter {
            it.substringBefore('/') == CleanYourTvConstants.LAUNCHER_PACKAGE
        }
        if (cleanHomeCandidates.isEmpty()) {
            val msg = "Aborted HOME switch: ${CleanYourTvConstants.LAUNCHER_PACKAGE} does not expose a valid HOME activity on this TV."
            _launcherStatus.value = preflight.copy(statusDetail = msg)
            return Result.failure(IllegalStateException(msg))
        }
        val targetHomeComponent = cleanHomeCandidates.firstOrNull {
            componentsMatch(it, CleanYourTvConstants.LAUNCHER_COMPONENT)
        } ?: if (cleanHomeCandidates.size == 1) {
            cleanHomeCandidates.single()
        } else {
            val msg = "Aborted HOME switch: ambiguous HOME activities for ${CleanYourTvConstants.LAUNCHER_PACKAGE}: ${cleanHomeCandidates.joinToString()}."
            _launcherStatus.value = preflight.copy(statusDetail = msg)
            return Result.failure(IllegalStateException(msg))
        }

        // Never disable stock launcher; validate, durably persist, and read-back-verify original HOME BEFORE switching.
        val candidateOrigPkg = preflight.currentHomePackage?.trim()?.ifBlank { null }
        val candidateOrigAct = preflight.currentHomeActivity?.trim()?.ifBlank { null }
        if (candidateOrigPkg != null && candidateOrigPkg != CleanYourTvConstants.LAUNCHER_PACKAGE) {
            val resolvedAct = if (validateOriginalHomeComponent(candidateOrigPkg, candidateOrigAct).first &&
                (queriedHomes.none { it.substringBefore('/') == candidateOrigPkg } ||
                    queriedHomes.any { componentsMatch(it, candidateOrigAct!!) })
            ) {
                candidateOrigAct!!
            } else {
                val matchingHomes = queriedHomes.filter { it.substringBefore('/') == candidateOrigPkg }
                if (matchingHomes.size == 1) {
                    matchingHomes.single()
                } else {
                    adbManager.resolveHomeActivityForPackage(candidateOrigPkg).getOrNull()
                        ?.takeIf { validateOriginalHomeComponent(candidateOrigPkg, it).first }
                        ?: candidateOrigAct
                }
            }

            val (validOrig, validReason) = validateOriginalHomeComponent(candidateOrigPkg, resolvedAct)
            if (!validOrig) {
                val msg = "Aborted HOME switch: discovered original HOME ($candidateOrigPkg / $resolvedAct) failed validation ($validReason)."
                _launcherStatus.value = preflight.copy(statusDetail = msg)
                return Result.failure(IllegalStateException(msg))
            }

            val saveRes = saveOriginalHome(candidateOrigPkg, resolvedAct!!)
            if (saveRes.isFailure) {
                val err = saveRes.exceptionOrNull()!!
                val msg = "Aborted HOME switch: failed to durably persist original HOME '$candidateOrigPkg' (${err.message})."
                _launcherStatus.value = preflight.copy(statusDetail = msg)
                return Result.failure(IllegalStateException(msg, err))
            }
        } else {
            val (persistedPkg, persistedAct) = loadOriginalHome()
            if (persistedPkg.isNullOrBlank() || persistedAct.isNullOrBlank()) {
                val msg = "Aborted HOME switch: no valid, verified original HOME backup exists in persistent storage."
                _launcherStatus.value = preflight.copy(statusDetail = msg)
                return Result.failure(IllegalStateException(msg))
            }
        }

        val setRes = adbManager.setDefaultHomeActivity(targetHomeComponent)
        if (setRes.isFailure) {
            val err = setRes.exceptionOrNull()!!
            val msg = "Firmware blocked programmatic HOME switch (${err.message}). Original HOME kept safely active. ${manualSwitchToCleanYourTvInstructions()}"
            _launcherStatus.value = preflight.copy(statusDetail = msg)
            return Result.failure(IllegalStateException(msg, err))
        }
        val setCmd = setRes.getOrThrow()
        if (!setCmd.isSuccess) {
            val detail = setCmd.stderr.ifBlank { setCmd.stdout }.ifBlank { "exit code ${setCmd.exitCode}" }
            val msg = "Firmware rejected HOME switch ($detail). Original HOME kept safely active. ${manualSwitchToCleanYourTvInstructions()}"
            _launcherStatus.value = preflight.copy(statusDetail = msg)
            return Result.failure(IllegalStateException(msg))
        }

        val launchRes = adbManager.launchHomeChooserOrOpenHome()
        val verified = refreshLauncherStatus().getOrElse { return Result.failure(it) }

        return if (verified.isCleanYourTvActiveHome && launchRes.isSuccess) {
            Result.success(verified)
        } else {
            val msg = "Default HOME still resolves to '${verified.currentHomePackage}'. ${manualSwitchToCleanYourTvInstructions()}"
            _launcherStatus.value = verified.copy(statusDetail = msg)
            Result.failure(IllegalStateException(msg))
        }
    }

    suspend fun restoreOriginalHomeLauncher(): Result<CleanYourTvLauncherStatus> {
        val (origPkg, origAct) = loadOriginalHome()
        val (validRecord, invalidReason) = validateOriginalHomeComponent(origPkg, origAct)
        if (!validRecord || origPkg == null || origAct == null) {
            val msg = "Cannot restore original HOME: no verified original HOME backup exists in persistent storage${invalidReason?.let { " ($it)" } ?: ""}. ${manualRestoreOriginalHomeInstructions(origPkg)}"
            _launcherStatus.value = _launcherStatus.value.copy(statusDetail = msg)
            return Result.failure(IllegalStateException(msg))
        }

        // Verify original launcher is enabled BEFORE attempting to resolve & set it as default HOME
        val enableRes = adbManager.enablePackage(origPkg)
        if (enableRes.isFailure) {
            val err = enableRes.exceptionOrNull()!!
            val msg = "Failed to enable original launcher '$origPkg' before setting HOME: ${err.message}. ${manualRestoreOriginalHomeInstructions(origPkg)}"
            _launcherStatus.value = _launcherStatus.value.copy(statusDetail = msg)
            return Result.failure(IllegalStateException(msg, err))
        }
        val enableCmd = enableRes.getOrThrow()
        if (!enableCmd.isSuccess) {
            val detail = enableCmd.stderr.ifBlank { enableCmd.stdout }.ifBlank { "exit code ${enableCmd.exitCode}" }
            val msg = "Failed to enable original launcher '$origPkg' before setting HOME: $detail. ${manualRestoreOriginalHomeInstructions(origPkg)}"
            _launcherStatus.value = _launcherStatus.value.copy(statusDetail = msg)
            return Result.failure(IllegalStateException(msg))
        }

        val installedAfterEnable = adbManager.listAllPackages().getOrElse { err ->
            val msg = "Unable to verify installed state of original launcher '$origPkg': ${err.message}"
            _launcherStatus.value = _launcherStatus.value.copy(statusDetail = msg)
            return Result.failure(IllegalStateException(msg, err))
        }
        if (!installedAfterEnable.contains(origPkg)) {
            val msg = "Original launcher '$origPkg' is not installed on TV; aborted HOME switch without guessing. ${manualRestoreOriginalHomeInstructions(origPkg)}"
            _launcherStatus.value = _launcherStatus.value.copy(
                isOriginalHomeAvailable = false,
                statusDetail = msg
            )
            return Result.failure(IllegalStateException(msg))
        }

        val disabledAfterEnable = adbManager.listDisabledPackages().getOrElse { err ->
            val msg = "Unable to verify enabled state of original launcher '$origPkg': ${err.message}"
            _launcherStatus.value = _launcherStatus.value.copy(statusDetail = msg)
            return Result.failure(IllegalStateException(msg, err))
        }
        if (disabledAfterEnable.contains(origPkg)) {
            val msg = "Original launcher '$origPkg' remains disabled on TV; aborted HOME switch. ${manualRestoreOriginalHomeInstructions(origPkg)}"
            _launcherStatus.value = _launcherStatus.value.copy(
                isOriginalHomeAvailable = false,
                statusDetail = msg
            )
            return Result.failure(IllegalStateException(msg))
        }

        // Discover real HOME-capable activities via PackageManager — NEVER guess a synthetic MainActivity component.
        val queriedHomes = adbManager.queryHomeActivities().getOrElse { err ->
            val msg = "Unable to query HOME-capable activities for '$origPkg' (${err.message}); aborted without guessing. ${manualRestoreOriginalHomeInstructions(origPkg)}"
            _launcherStatus.value = _launcherStatus.value.copy(statusDetail = msg)
            return Result.failure(IllegalStateException(msg, err))
        }
        val pkgHomeActivities = queriedHomes.filter { it.substringBefore('/') == origPkg }
        val targetComponent: String = when {
            pkgHomeActivities.any { componentsMatch(it, origAct) } -> {
                pkgHomeActivities.first { componentsMatch(it, origAct) }
            }
            pkgHomeActivities.size == 1 -> {
                pkgHomeActivities.single()
            }
            pkgHomeActivities.isEmpty() -> {
                val resolved = adbManager.resolveHomeActivityForPackage(origPkg).getOrNull()
                if (resolved != null && validateOriginalHomeComponent(origPkg, resolved).first) {
                    resolved
                } else {
                    val msg = "Original launcher '$origPkg' has no discoverable HOME-capable activity on TV (expected '$origAct'); aborted without guessing. ${manualRestoreOriginalHomeInstructions(origPkg)}"
                    _launcherStatus.value = _launcherStatus.value.copy(statusDetail = msg)
                    return Result.failure(IllegalStateException(msg))
                }
            }
            else -> {
                val msg = "Multiple HOME activities found for '$origPkg' (${pkgHomeActivities.joinToString()}) and stored component '$origAct' was not matched; aborted without guessing. ${manualRestoreOriginalHomeInstructions(origPkg)}"
                _launcherStatus.value = _launcherStatus.value.copy(statusDetail = msg)
                return Result.failure(IllegalStateException(msg))
            }
        }

        val setRes = adbManager.setDefaultHomeActivity(targetComponent)
        if (setRes.isFailure) {
            val err = setRes.exceptionOrNull()!!
            val msg = "Failed to restore default HOME to '$targetComponent': ${err.message}. ${manualRestoreOriginalHomeInstructions(origPkg)}"
            _launcherStatus.value = _launcherStatus.value.copy(statusDetail = msg)
            return Result.failure(IllegalStateException(msg, err))
        }
        val setCmd = setRes.getOrThrow()
        if (!setCmd.isSuccess) {
            val detail = setCmd.stderr.ifBlank { setCmd.stdout }.ifBlank { "exit code ${setCmd.exitCode}" }
            val msg = "Firmware rejected default HOME restoration to '$targetComponent' ($detail). ${manualRestoreOriginalHomeInstructions(origPkg)}"
            _launcherStatus.value = _launcherStatus.value.copy(statusDetail = msg)
            return Result.failure(IllegalStateException(msg))
        }

        adbManager.launchHomeChooserOrOpenHome()

        val verified = refreshLauncherStatus().getOrElse { return Result.failure(it) }
        return if (verified.currentHomePackage == origPkg) {
            prefs?.edit()?.putLong(KEY_ORIG_HOME_LAST_RESTORED_AT, timeProvider())?.commit()
            Result.success(verified)
        } else {
            val msg = "Restore command ran for '$targetComponent', but active HOME still reports '${verified.currentHomePackage}' instead of '$origPkg'. ${manualRestoreOriginalHomeInstructions(origPkg)}"
            _launcherStatus.value = verified.copy(statusDetail = msg)
            Result.failure(IllegalStateException(msg))
        }
    }

    private fun saveOriginalHome(pkg: String, activity: String): Result<Pair<String, String>> {
        val cleanPkg = pkg.trim()
        val cleanAct = activity.trim()
        val (valid, invalidReason) = validateOriginalHomeComponent(cleanPkg, cleanAct)
        if (!valid) {
            return Result.failure(IllegalArgumentException(invalidReason ?: "Invalid original HOME component."))
        }
        if (simulatePersistenceFailureForTest) {
            return Result.failure(IllegalStateException("Simulated original HOME persistence failure"))
        }
        val p = prefs
            ?: return Result.failure(IllegalStateException("Persistent storage unavailable (SharedPreferences is null) — cannot persist original HOME '$cleanPkg'."))

        val existingCapturedAt = p.getLong(KEY_ORIG_HOME_CAPTURED_AT, 0L)
        val capturedAt = if (existingCapturedAt > 0L) existingCapturedAt else timeProvider()

        val committed = p.edit()
            .putString(KEY_ORIG_HOME_PKG, cleanPkg)
            .putString(KEY_ORIG_HOME_ACT, cleanAct)
            .putBoolean(KEY_ORIG_HOME_VALID, true)
            .putLong(KEY_ORIG_HOME_CAPTURED_AT, capturedAt)
            .commit()
        if (!committed) {
            return Result.failure(IllegalStateException("SharedPreferences commit() returned false when saving original HOME '$cleanPkg'"))
        }

        val readPkg = p.getString(KEY_ORIG_HOME_PKG, null)?.trim()?.ifBlank { null }
        val readAct = p.getString(KEY_ORIG_HOME_ACT, null)?.trim()?.ifBlank { null }
        val readValid = p.getBoolean(KEY_ORIG_HOME_VALID, false)
        val readCapturedAt = p.getLong(KEY_ORIG_HOME_CAPTURED_AT, 0L)
        val (readComponentValid, _) = validateOriginalHomeComponent(readPkg, readAct)

        if (!readValid ||
            !readComponentValid ||
            readPkg != cleanPkg ||
            readAct != cleanAct ||
            readCapturedAt != capturedAt
        ) {
            p.edit().putBoolean(KEY_ORIG_HOME_VALID, false).commit()
            return Result.failure(IllegalStateException("Read-back verification mismatch after saving original HOME '$cleanPkg' ($readPkg / $readAct / valid=$readValid)"))
        }
        return Result.success(cleanPkg to cleanAct)
    }

    private fun loadOriginalHome(): Pair<String?, String?> {
        val p = prefs ?: return null to null
        val isValidMarked = p.getBoolean(KEY_ORIG_HOME_VALID, false)
        if (!isValidMarked) return null to null
        val pkg = p.getString(KEY_ORIG_HOME_PKG, null)?.trim()?.ifBlank { null }
        val act = p.getString(KEY_ORIG_HOME_ACT, null)?.trim()?.ifBlank { null }
        return if (validateOriginalHomeComponent(pkg, act).first) {
            pkg to act
        } else {
            null to null
        }
    }

    private fun buildStatusSummary(
        sdk: Int,
        installed: Boolean,
        active: Boolean,
        currentHome: String?,
        filePushOk: Boolean
    ): String {
        val parts = mutableListOf<String>()
        parts.add("TV SDK $sdk")
        parts.add(if (installed) "CleanYourTV installed" else "CleanYourTV not installed")
        parts.add("Active HOME: ${currentHome ?: "Unknown"}")
        if (active) parts.add("Verified Active")
        if (!filePushOk) parts.add("Scoped storage restriction may limit config push")
        return parts.joinToString(" • ")
    }
}
