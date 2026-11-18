package com.gala.motetv.debloat

import com.gala.motetv.adb.AdbConnectionManager
import com.gala.motetv.adb.TvDeviceInfo

class TvPackageScanner(
    private val adbManager: AdbConnectionManager,
    private val repository: DebloatRepository,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun scanTvPackages(): Result<TvScanResult> {
        val devInfo = adbManager.inspectTvDevice().getOrElse { return Result.failure(it) }
        val allPackages = adbManager.listAllPackages().getOrElse { return Result.failure(it) }
        val disabledSet = adbManager.listDisabledPackages().getOrElse { return Result.failure(it) }
        val systemSet = adbManager.listSystemPackages().getOrDefault(emptySet())
        val modifiedSet = repository.getModifiedPackages()

        val result = buildScanResult(
            deviceInfo = devInfo,
            installedPackages = allPackages,
            disabledPackages = disabledSet,
            modifiedByMoteTv = modifiedSet,
            timestampMs = timeProvider(),
            systemPackages = systemSet
        )
        return Result.success(result)
    }

    fun buildScanResult(
        deviceInfo: TvDeviceInfo,
        installedPackages: List<String>,
        disabledPackages: Set<String>,
        modifiedByMoteTv: Set<String> = emptySet(),
        timestampMs: Long = timeProvider(),
        systemPackages: Set<String> = emptySet()
    ): TvScanResult {
        val exactXiaomiStickMatch = deviceInfo.isXiaomiDevice &&
            PackageSafetyGuard.matchesVerifiedXiaomiStickModel(deviceInfo)
        val isUnverifiedXiaomiVariant = deviceInfo.isXiaomiDevice && !exactXiaomiStickMatch

        val notice = when {
            exactXiaomiStickMatch ->
                "Verified Xiaomi Mi TV Stick (${deviceInfo.model} / ${deviceInfo.deviceName}, SDK ${deviceInfo.sdkVersion}). Issue #884, stock launchers & unknown system components are permanently locked."
            isUnverifiedXiaomiVariant ->
                "Unverified Xiaomi variant (${deviceInfo.model} / ${deviceInfo.deviceName}). Generic Xiaomi lists are NOT auto-approved — unknown system packages fail closed and optional apps require manual review."
            else ->
                "Model '${deviceInfo.manufacturer} ${deviceInfo.model}' (Android ${deviceInfo.androidVersion}, SDK ${deviceInfo.sdkVersion}) has no exact Xiaomi Stick catalog match — system packages fail closed and other apps default to manual review."
        }

        val installedNormalized = installedPackages.map { PackageSafetyGuard.normalizePackageName(it) }.toSet()
        val disabledNormalized = disabledPackages.map { PackageSafetyGuard.normalizePackageName(it) }.toSet()
        val modifiedNormalized = modifiedByMoteTv.map { PackageSafetyGuard.normalizePackageName(it) }.toSet()
        val systemNormalized = systemPackages.map { PackageSafetyGuard.normalizePackageName(it) }.toSet()

        val scanned = installedPackages
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { PackageSafetyGuard.normalizePackageName(it) }
            .map { pkg ->
                val normalized = PackageSafetyGuard.normalizePackageName(pkg)
                classifyPackage(
                    packageName = pkg,
                    normalizedName = normalized,
                    isEnabled = !disabledNormalized.contains(normalized),
                    deviceInfo = deviceInfo,
                    installedSet = installedNormalized,
                    systemSet = systemNormalized,
                    modified = modifiedNormalized.contains(normalized)
                )
            }
            .sortedWith(
                compareBy<ScannedPackage> { it.category.sortOrder }
                    .thenBy { it.appName.lowercase() }
            )

        return TvScanResult(
            scannedAtMs = timestampMs,
            deviceInfo = deviceInfo,
            packages = scanned,
            hasExactModelCatalogMatch = exactXiaomiStickMatch,
            modelSafetyNotice = notice
        )
    }

    private fun classifyPackage(
        packageName: String,
        normalizedName: String,
        isEnabled: Boolean,
        deviceInfo: TvDeviceInfo,
        installedSet: Set<String>,
        systemSet: Set<String>,
        modified: Boolean
    ): ScannedPackage {
        val currentHomeNormalized = deviceInfo.currentHomePackage?.let(PackageSafetyGuard::normalizePackageName)
        val isCurrentHome = currentHomeNormalized != null && normalizedName == currentHomeNormalized
        val isStockLauncher = CleanYourTvPackageCatalog.STOCK_LAUNCHER_PACKAGES.contains(normalizedName)
        val isOriginalLauncherFlag = isCurrentHome || isStockLauncher

        val decision = PackageSafetyGuard.evaluateDisableCandidate(
            packageName = normalizedName,
            deviceInfo = deviceInfo,
            installedPackages = installedSet,
            verifiedReplacementHomeInstalled = false,
            verifiedReplacementHomeActive = false,
            systemPackages = systemSet
        )

        val known = CleanYourTvPackageCatalog.KNOWN_BY_PACKAGE[normalizedName]

        val (alwaysBlocked, alwaysBlockReason) = PackageSafetyGuard.isAlwaysBlockedPackage(normalizedName)
        if (alwaysBlocked || isOriginalLauncherFlag) {
            val blockMessage = alwaysBlockReason
                ?: decision.reason
                ?: "Essential Android TV system or HOME launcher package — permanently blocked."
            return ScannedPackage(
                packageName = packageName,
                appName = known?.appName ?: deriveFriendlyName(packageName),
                category = known?.category ?: PackageCategory.SYSTEM_ESSENTIAL,
                isEnabled = isEnabled,
                recommendation = DebloatRecommendation.NEVER_DISABLE,
                reason = blockMessage,
                riskLevel = RiskLevel.CRITICAL_BLOCKED,
                requiresOptionalPrompt = false,
                isBlockedFromDisable = true,
                blockReason = blockMessage,
                isOriginalHomeLauncher = isOriginalLauncherFlag,
                modifiedByMoteTv = modified
            )
        }

        if (known != null) {
            val blocked = !decision.allowed
            val perPackageDeviceMatch = PackageSafetyGuard.matchesPackageDeviceCatalog(known, deviceInfo)
            val effectiveRec = when {
                blocked -> DebloatRecommendation.NEVER_DISABLE
                known.recommendation == DebloatRecommendation.SAFE_TO_DISABLE && !perPackageDeviceMatch ->
                    DebloatRecommendation.OPTIONAL_ASK_USER
                else -> known.recommendation
            }
            val effectiveReason = when {
                blocked -> decision.reason ?: known.reason
                known.recommendation == DebloatRecommendation.SAFE_TO_DISABLE && !perPackageDeviceMatch ->
                    "${known.reason} Not explicitly verified for ${deviceInfo.manufacturer} ${deviceInfo.model} — manual review required."
                else -> known.reason
            }
            return ScannedPackage(
                packageName = packageName,
                appName = known.appName,
                category = known.category,
                isEnabled = isEnabled,
                recommendation = effectiveRec,
                reason = effectiveReason,
                riskLevel = if (blocked) RiskLevel.CRITICAL_BLOCKED else known.riskLevel,
                requiresOptionalPrompt = if (blocked) false else decision.requiresExplicitOptionalPrompt,
                isBlockedFromDisable = blocked,
                blockReason = if (blocked) effectiveReason else null,
                isOriginalHomeLauncher = isOriginalLauncherFlag,
                modifiedByMoteTv = modified
            )
        }

        // Unknown package: fail closed for Android/AOSP/Google/OEM/system packages
        if (!decision.allowed) {
            val blockMsg = decision.reason
                ?: "Unknown system package ($packageName) is blocked by fail-closed safety policy."
            return ScannedPackage(
                packageName = packageName,
                appName = deriveFriendlyName(packageName),
                category = PackageCategory.SYSTEM_ESSENTIAL,
                isEnabled = isEnabled,
                recommendation = DebloatRecommendation.NEVER_DISABLE,
                reason = blockMsg,
                riskLevel = RiskLevel.CRITICAL_BLOCKED,
                requiresOptionalPrompt = false,
                isBlockedFromDisable = true,
                blockReason = blockMsg,
                isOriginalHomeLauncher = isOriginalLauncherFlag,
                modifiedByMoteTv = modified
            )
        }

        // Unknown third-party user application: manually selectable with warning, never auto-disabled
        val isStreaming = normalizedName.contains("netflix") ||
            normalizedName.contains("youtube") ||
            normalizedName.contains("hulu") ||
            normalizedName.contains("disney") ||
            normalizedName.contains("spotify") ||
            normalizedName.contains("plex")
        val inferredCategory = if (isStreaming) PackageCategory.STREAMING else PackageCategory.UNKNOWN_UNVERIFIED
        val reason = if (isStreaming) {
            "Streaming application — optional."
        } else {
            "Unverified third-party package not in CleanYourTV catalog. Review carefully before disabling; never auto-disabled."
        }

        return ScannedPackage(
            packageName = packageName,
            appName = deriveFriendlyName(packageName),
            category = inferredCategory,
            isEnabled = isEnabled,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = reason,
            riskLevel = RiskLevel.MODERATE,
            requiresOptionalPrompt = true,
            isBlockedFromDisable = false,
            blockReason = null,
            isOriginalHomeLauncher = false,
            modifiedByMoteTv = modified
        )
    }

    private fun deriveFriendlyName(packageName: String): String {
        val last = packageName.substringAfterLast('.').ifBlank { packageName }
        return last.replace('_', ' ')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
