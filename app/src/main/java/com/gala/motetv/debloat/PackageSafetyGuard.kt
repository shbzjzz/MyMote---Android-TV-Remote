package com.gala.motetv.debloat

import com.gala.motetv.adb.TvDeviceInfo

/**
 * Central, non-bypassable safety policy enforcing CleanYourTV Issue #884 invariants across
 * package scanning, batch debloat execution, and the lowest-level ADB execution boundary.
 *
 * No user override, force flag, developer mode, or replacement launcher state can bypass a
 * hard-blocked package. Unknown system packages fail closed.
 */
object PackageSafetyGuard {

    /**
     * Scoped package-family prefixes permanently blocked from disabling.
     * Matched against trimmed, lowercased package names.
     */
    val BLOCKED_PREFIXES: List<String> = listOf(
        // Runtime resource overlays & themes
        "com.google.android.overlay.modules.",
        "com.google.android.overlay.",
        "com.android.overlay.",
        "android.overlay.",
        "com.android.theme.",
        "com.android.internal.",

        // Core providers & system servers
        "com.android.providers.",
        "com.google.android.providers.",
        "com.android.server.",

        // Network & Wi-Fi stacks
        "com.android.networkstack",
        "com.google.android.networkstack",
        "com.android.wifi",
        "com.google.android.wifi",
        "com.android.connectivity.",
        "com.google.android.connectivity.",

        // Bluetooth & hardware input / IME
        "com.android.bluetooth",
        "com.google.android.bluetooth",
        "com.android.inputmethod.",
        "com.google.android.inputmethod.",
        "com.google.android.accessibility.",
        "com.android.accessibility.",

        // Permission, PackageInstaller, Extension Services & Module Metadata variants
        "com.google.android.permissioncontroller.",
        "com.android.permissioncontroller.",
        "android.permissioncontroller.",
        "com.google.android.packageinstaller.",
        "com.android.packageinstaller.",
        "android.packageinstaller.",
        "com.google.android.ext.",
        "com.android.ext.",
        "android.ext.",
        "com.google.android.modulemetadata.",
        "com.android.modulemetadata.",
        "android.modulemetadata.",

        // CTS shims & Mainline core
        "com.android.cts.",
        "com.google.android.cts.",
        "com.android.apex.cts.shim",

        // Setup / provisioning / stock launchers
        "com.google.android.tungsten.",
        "com.google.android.setupwizard.",
        "com.android.setupwizard.",
        "com.google.android.tvlauncher.",
        "com.google.android.apps.tv.launcherx.",
        "com.mitv.tvhome.settings",
        "com.xiaomi.mitv.settings",

        // TV tuner / HAL / SoC hardware services
        "com.android.tv.tuner",
        "com.google.android.tv.tuner",
        "com.droidlogic."
    )

    private val SYSTEM_NAMESPACES: List<String> = listOf(
        "android.",
        "com.android.",
        "com.google.android.",
        "com.xiaomi.",
        "com.miui.",
        "com.mitv.",
        "com.xm.",
        "com.droidlogic.",
        "com.amlogic.",
        "com.mediatek.",
        "com.realtek.",
        "com.sony.",
        "com.tcl.",
        "com.hisense.",
        "com.philips.",
        "com.nvidia."
    )

    private val ANDROID_AOSP_GOOGLE_NAMESPACES: List<String> = listOf(
        "android.",
        "com.android.",
        "com.google.android."
    )

    /**
     * Segment-level rules ONLY evaluated inside trusted Android/OEM system namespaces so unrelated
     * third-party packages (e.g. `com.example.hdmichecker`) are never falsely blocked.
     */
    private val SYSTEM_NAMESPACE_PROTECTED_SEGMENTS: List<String> = listOf(
        "permissioncontroller",
        "packageinstaller",
        "ext.services",
        "ext.shared",
        "modulemetadata",
        "setupwraith",
        "setupwizard",
        "provision",
        "tvinput",
        "hdmicec",
        "keyguard",
        "inputdevices",
        "talkback",
        "switchaccess",
        "remotecontrol",
        "remotecontroller",
        "tv.remote.service"
    )

    data class SafetyDecision(
        val allowed: Boolean,
        val requiresExplicitOptionalPrompt: Boolean,
        val reason: String? = null
    )

    fun normalizePackageName(packageName: String): String = packageName.trim().lowercase()

    fun isAlwaysBlockedPackage(packageName: String): Pair<Boolean, String?> {
        val lower = normalizePackageName(packageName)
        if (lower.isBlank()) {
            return true to "Blank package name cannot be disabled."
        }

        val exactReason = CleanYourTvPackageCatalog.CRITICAL_SYSTEM_EXACT[lower]
        if (exactReason != null) {
            return true to exactReason
        }

        if (CleanYourTvPackageCatalog.STOCK_LAUNCHER_PACKAGES.contains(lower)) {
            return true to "Stock HOME launcher ($lower) is permanently protected to guarantee a fallback HOME option."
        }

        if (BLOCKED_PREFIXES.any { prefix ->
                if (prefix.endsWith(".")) {
                    lower.startsWith(prefix) || lower == prefix.dropLast(1)
                } else {
                    lower == prefix || lower.startsWith("$prefix.")
                }
            }
        ) {
            return true to "Essential Android TV / overlay / setup / network / input component — permanently blocked to prevent bootloop or lost recovery."
        }

        if (isInSystemNamespace(lower)) {
            val matchedSegment = SYSTEM_NAMESPACE_PROTECTED_SEGMENTS.firstOrNull { token ->
                matchesSegmentToken(lower, token)
            }
            if (matchedSegment != null) {
                return true to "Protected Android/OEM system dependency ($matchedSegment) — permanently blocked."
            }
            // Protect overlay sub-modules inside system/OEM namespaces (e.g. com.xiaomi.mitv.overlay.*)
            if (matchesSegmentToken(lower, "overlay")) {
                return true to "Protected Android/OEM runtime overlay package — permanently blocked."
            }
        }

        return false to null
    }

    /**
     * Execution-boundary check invoked directly by the ADB command layer before sending any
     * `pm disable-user` / `pm disable` / `pm hide` command to the TV.
     */
    fun checkExecutionBoundaryBlock(
        packageName: String,
        currentHomePackage: String? = null,
        systemPackages: Set<String> = emptySet(),
        deviceInfo: TvDeviceInfo? = null
    ): Pair<Boolean, String?> {
        val lower = normalizePackageName(packageName)
        val (alwaysBlocked, reason) = isAlwaysBlockedPackage(lower)
        if (alwaysBlocked) {
            return true to reason
        }

        val normalizedHome = currentHomePackage?.let(::normalizePackageName)?.ifBlank { null }
        if (normalizedHome != null && lower == normalizedHome) {
            return true to "Active HOME launcher ($lower) cannot be disabled."
        }

        val known = CleanYourTvPackageCatalog.KNOWN_BY_PACKAGE[lower]
        if (known != null) {
            if (known.recommendation == DebloatRecommendation.NEVER_DISABLE ||
                known.recommendation == DebloatRecommendation.KEEP
            ) {
                return true to known.reason
            }
            return false to null
        }

        // Fail closed for unknown system packages even at the lowest ADB execution boundary.
        if (isAndroidAospGoogleNamespace(lower) || isSystemPackage(lower, systemPackages)) {
            return true to "Unknown system package ($lower) is blocked by fail-closed safety policy."
        }

        if (deviceInfo != null && isInSystemNamespace(lower)) {
            return true to "Unknown manufacturer/platform component ($lower) is blocked by fail-closed safety policy."
        }

        return false to null
    }

    /**
     * Evaluates whether [packageName] can be disabled for [deviceInfo].
     * Note: [verifiedReplacementHomeInstalled] and [verifiedReplacementHomeActive] are retained
     * for API compatibility, but original stock launchers remain permanently hard-blocked so
     * the TV always retains a fallback HOME option.
     */
    fun evaluateDisableCandidate(
        packageName: String,
        deviceInfo: TvDeviceInfo,
        installedPackages: Set<String>,
        verifiedReplacementHomeInstalled: Boolean,
        verifiedReplacementHomeActive: Boolean,
        systemPackages: Set<String> = emptySet()
    ): SafetyDecision {
        val lower = normalizePackageName(packageName)
        val (alwaysBlocked, blockReason) = isAlwaysBlockedPackage(lower)
        if (alwaysBlocked) {
            return SafetyDecision(allowed = false, requiresExplicitOptionalPrompt = false, reason = blockReason)
        }

        // Active HOME protection: never disable whichever launcher is currently active on the TV
        val normalizedCurrentHome = deviceInfo.currentHomePackage?.let(::normalizePackageName)?.ifBlank { null }
        if (normalizedCurrentHome != null && lower == normalizedCurrentHome) {
            return SafetyDecision(
                allowed = false,
                requiresExplicitOptionalPrompt = false,
                reason = "Active HOME launcher ($lower) is permanently protected and cannot be disabled."
            )
        }

        val known = CleanYourTvPackageCatalog.KNOWN_BY_PACKAGE[lower]
        if (known != null) {
            if (known.recommendation == DebloatRecommendation.NEVER_DISABLE ||
                known.recommendation == DebloatRecommendation.KEEP
            ) {
                return SafetyDecision(
                    allowed = false,
                    requiresExplicitOptionalPrompt = false,
                    reason = known.reason
                )
            }
            val modelMatched = matchesPackageDeviceCatalog(known, deviceInfo)
            val needsPrompt = known.requiresOptionalPrompt || !modelMatched
            return SafetyDecision(
                allowed = true,
                requiresExplicitOptionalPrompt = needsPrompt,
                reason = known.reason
            )
        }

        // Unknown package: fail closed for Android/AOSP/Google & OEM/platform system components.
        if (isAndroidAospGoogleNamespace(lower)) {
            return SafetyDecision(
                allowed = false,
                requiresExplicitOptionalPrompt = false,
                reason = "Unknown Android/AOSP/Google system component ($lower) defaults to NEVER_DISABLE until individually verified for this model."
            )
        }

        if (isSystemPackage(lower, systemPackages)) {
            return SafetyDecision(
                allowed = false,
                requiresExplicitOptionalPrompt = false,
                reason = "Unknown system/manufacturer package ($lower) is not verified in catalog — blocked by fail-closed safety policy."
            )
        }

        // Unknown third-party user application: manually selectable with explicit warning, never auto-selected.
        return SafetyDecision(
            allowed = true,
            requiresExplicitOptionalPrompt = true,
            reason = "Unverified third-party package ($lower) not in catalog. Review carefully before disabling."
        )
    }

    fun isAndroidAospGoogleNamespace(packageName: String): Boolean {
        val lower = normalizePackageName(packageName)
        return lower == "android" || ANDROID_AOSP_GOOGLE_NAMESPACES.any { lower.startsWith(it) }
    }

    fun isInSystemNamespace(packageName: String): Boolean {
        val lower = normalizePackageName(packageName)
        return lower == "android" || SYSTEM_NAMESPACES.any { lower.startsWith(it) }
    }

    fun isSystemPackage(packageName: String, systemPackages: Set<String> = emptySet()): Boolean {
        val lower = normalizePackageName(packageName)
        if (isInSystemNamespace(lower)) return true
        return systemPackages.any { normalizePackageName(it) == lower }
    }

    fun matchesVerifiedXiaomiStickModel(deviceInfo: TvDeviceInfo): Boolean {
        if (!deviceInfo.isXiaomiDevice) return false
        val id = "${deviceInfo.model} ${deviceInfo.deviceName} ${deviceInfo.productBoard}".lowercase()
        return CleanYourTvPackageCatalog.VERIFIED_XIAOMI_STICK_IDENTIFIERS.any { id.contains(it) }
    }

    /**
     * Returns true ONLY if [known] is explicitly verified for [deviceInfo]'s detected manufacturer
     * AND exact model metadata. Cross-manufacturer packages (e.g., Sony/TCL on Xiaomi) and unknown
     * models return false so they default cleanly to manual review.
     */
    fun matchesPackageDeviceCatalog(known: KnownPackageDescriptor, deviceInfo: TvDeviceInfo): Boolean {
        val detectedMfg = detectedManufacturerKey(deviceInfo)

        if (known.supportedManufacturers.isNotEmpty()) {
            val mfgMatched = known.supportedManufacturers.any { expectedMfg ->
                when (expectedMfg.lowercase()) {
                    "xiaomi" -> deviceInfo.isXiaomiDevice || detectedMfg == "xiaomi"
                    else -> detectedMfg == expectedMfg.lowercase()
                }
            }
            if (!mfgMatched) return false
        }

        if (deviceInfo.isXiaomiDevice) {
            if (known.verifiedXiaomiStickModels.isEmpty()) return false
            val id = "${deviceInfo.model} ${deviceInfo.deviceName} ${deviceInfo.productBoard}".lowercase()
            return known.verifiedXiaomiStickModels.any { id.contains(it.lowercase()) }
        }

        // Non-Xiaomi or unknown manufacturer: require explicit manufacturer verification on the package
        return known.supportedManufacturers.isNotEmpty() && detectedMfg.isNotBlank() && detectedMfg != "unknown"
    }

    private fun matchesSegmentToken(packageNameLower: String, token: String): Boolean {
        return packageNameLower == token ||
            packageNameLower.endsWith(".$token") ||
            packageNameLower.contains(".$token.")
    }

    private fun detectedManufacturerKey(deviceInfo: TvDeviceInfo): String {
        if (deviceInfo.isXiaomiDevice) return "xiaomi"
        return deviceInfo.manufacturer.trim().lowercase()
    }
}
