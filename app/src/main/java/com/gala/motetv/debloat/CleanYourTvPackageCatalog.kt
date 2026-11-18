package com.gala.motetv.debloat

/**
 * Curated CleanYourTV package classification catalog grounded in Gathin23/cleanyourtv,
 * including Xiaomi Mi TV Stick Issue #884 safeguards and strict per-package manufacturer/model rules.
 */
object CleanYourTvPackageCatalog {

    /**
     * Exact model/board identifiers with verified Mi TV Stick coverage.
     * Any variant outside this set receives strict per-package manual review.
     */
    val VERIFIED_XIAOMI_STICK_IDENTIFIERS: Set<String> = setOf(
        "aquaman",
        "mitv-aesp0",
        "mi tv stick",
        "mitv stick",
        "yoda" // Mi TV Stick 4K
    )

    /**
     * Recognized original stock HOME launcher packages.
     * Permanently protected so the TV always retains a fallback HOME launcher even when
     * CleanYourTV Home is installed and active.
     */
    val STOCK_LAUNCHER_PACKAGES: Set<String> = setOf(
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.leanbacklauncher",
        "com.android.tv.launcher",
        "com.android.launcher",
        "com.android.launcher2",
        "com.android.launcher3",
        "com.mitv.tvhome",
        "com.sony.dtv.osat.launcher",
        "com.sony.dtv.launcher",
        "com.tcl.tvlauncher",
        "com.tcl.launcher",
        "com.hisense.tv.launcher",
        "com.philips.tv.launcher",
        "com.amazon.tv.launcher"
    )

    val CRITICAL_SYSTEM_EXACT: Map<String, String> = mapOf(
        // CleanYourTV Issue #884 brick-risk packages: Permissions & Package Management
        "com.google.android.permissioncontroller" to "Android Runtime Permission Controller (CleanYourTV Issue #884 bootloop guard)",
        "com.android.permissioncontroller" to "Android Permission Controller (boot critical)",
        "android.permissioncontroller" to "AOSP Permission Controller (boot critical)",
        "com.google.android.packageinstaller" to "Package Installer (CleanYourTV Issue #884 bootloop guard)",
        "com.android.packageinstaller" to "Android Package Installer (boot critical)",
        "android.packageinstaller" to "AOSP Package Installer (boot critical)",
        "com.android.defcontainer" to "Package Default Container Service",
        "com.google.android.defcontainer" to "Google Package Default Container Service",
        "com.android.packageverifier" to "Android Package Verifier",
        "com.google.android.packageverifier" to "Google Package Verifier",

        // CleanYourTV Issue #884 brick-risk packages: Extension Services, Module Metadata & CTS Shims
        "com.google.android.ext.services" to "Android Services Library (CleanYourTV Issue #884 bootloop guard)",
        "com.android.ext.services" to "Android Services Library (boot critical)",
        "android.ext.services" to "AOSP Extension Services (boot critical)",
        "com.google.android.ext.shared" to "Android Shared Library (CleanYourTV Issue #884 bootloop guard)",
        "com.android.ext.shared" to "Android Shared Library (boot critical)",
        "android.ext.shared" to "AOSP Shared Library (boot critical)",
        "com.google.android.modulemetadata" to "Mainline Module Metadata (CleanYourTV Issue #884 bootloop guard)",
        "com.android.modulemetadata" to "Mainline Module Metadata (boot critical)",
        "android.modulemetadata" to "AOSP Mainline Module Metadata (boot critical)",
        "com.android.cts.ctsshim" to "Android CTS Compatibility Shim",
        "com.android.cts.priv.ctsshim" to "Android Privileged CTS Compatibility Shim",

        // Setup & Provisioning (Issue #884: SetupWraith holds Wi-Fi / first-boot state)
        "com.google.android.tungsten.setupwraith" to "Android TV Setup Wizard / SetupWraith (CleanYourTV Issue #884 boot & Wi-Fi guard)",
        "com.google.android.setupwizard" to "Google Setup Wizard",
        "com.android.setupwizard" to "Android Setup Wizard",
        "com.android.tv.provision" to "Android TV Setup Provisioning",
        "com.google.android.tv.provision" to "Google TV Setup Provisioning",
        "com.android.provision" to "AOSP Provisioning",
        "com.google.android.partnersetup" to "Google Partner Setup & Provisioning Helper",
        "com.android.managedprovisioning" to "Android Managed Provisioning",
        "com.xiaomi.mitv.setup" to "Xiaomi TV Setup Wizard",
        "com.xiaomi.mitv.wizard" to "Xiaomi TV Provisioning Wizard",
        "com.miui.tv.setup" to "MiUI TV Setup Component",

        // Original Stock HOME Launchers & CleanYourTV Home (never disable fallback HOME)
        "com.google.android.tvlauncher" to "Android TV Home (Stock Launcher — permanently protected for HOME recovery)",
        "com.google.android.apps.tv.launcherx" to "Google TV Home (Stock Launcher — permanently protected for HOME recovery)",
        "com.google.android.leanbacklauncher" to "Android TV Leanback Launcher (Stock Launcher — permanently protected)",
        "com.android.tv.launcher" to "AOSP TV Launcher (Stock Launcher — permanently protected)",
        "com.android.launcher" to "AOSP Launcher (Stock Launcher — permanently protected)",
        "com.android.launcher2" to "AOSP Launcher2 (Stock Launcher — permanently protected)",
        "com.android.launcher3" to "AOSP Launcher3 (Stock Launcher — permanently protected)",
        "com.mitv.tvhome" to "Xiaomi Stock TV Launcher (permanently protected for HOME recovery)",
        "com.sony.dtv.osat.launcher" to "Sony Stock TV Launcher (permanently protected)",
        "com.sony.dtv.launcher" to "Sony TV Launcher (permanently protected)",
        "com.tcl.tvlauncher" to "TCL Stock TV Launcher (permanently protected)",
        "com.tcl.launcher" to "TCL Launcher (permanently protected)",
        "com.hisense.tv.launcher" to "Hisense Stock TV Launcher (permanently protected)",
        "com.philips.tv.launcher" to "Philips Stock TV Launcher (permanently protected)",
        "com.amazon.tv.launcher" to "Amazon Fire TV Launcher (permanently protected)",
        "com.gathin.tvhome" to "CleanYourTV Home Launcher",

        // Core OS, SystemUI, Settings, Shell & Essential Services
        "android" to "Android OS System Framework",
        "com.android.systemserver" to "Android System Server",
        "com.android.shell" to "ADB & System Shell Runtime",
        "com.android.adbd" to "ADB Daemon",
        "com.android.systemui" to "System UI & Power/Volume Overlays",
        "com.google.android.systemui" to "Google System UI",
        "com.android.settings" to "System Settings",
        "com.android.tv.settings" to "Android TV Settings",
        "com.google.android.tv.settings" to "Google TV Settings",
        "com.android.settings.intelligence" to "Settings Intelligence",
        "com.xiaomi.mitv.settings" to "Xiaomi TV System Settings",
        "com.mitv.tvhome.settings" to "Xiaomi TV Settings Helper",
        "com.android.intentresolver" to "Android Intent Resolver (required for HOME / chooser resolution)",
        "com.google.android.intentresolver" to "Google Intent Resolver (required for HOME / chooser resolution)",
        "com.android.keyguard" to "System Keyguard",
        "com.android.keychain" to "Android KeyChain Credential Store",
        "com.android.certinstaller" to "Certificate Installer",
        "com.android.companiondevicemanager" to "Companion Device Manager",
        "com.android.externalstorage" to "External Storage Provider",
        "com.android.documentsui" to "Files / Documents UI",
        "com.google.android.documentsui" to "Google Files / Documents UI",
        "com.android.location.fused" to "Fused Location Service",
        "com.android.dynsystem" to "Dynamic System Updates",
        "com.android.se" to "Secure Element Service",
        "com.android.proxyhandler" to "Network Proxy Handler",
        "com.android.pacprocessor" to "PAC Proxy Processor",
        "com.android.captiveportallogin" to "Captive Portal Login",
        "com.google.android.captiveportallogin" to "Google Captive Portal Login",

        // Core Content Providers
        "com.android.providers.settings" to "Settings Storage Provider",
        "com.android.providers.media" to "Media Storage Provider",
        "com.android.providers.media.module" to "Media Provider Module",
        "com.google.android.providers.media.module" to "Google Media Provider Module",
        "com.android.providers.downloads" to "Download Manager Provider",
        "com.android.providers.downloads.ui" to "Download Manager UI",
        "com.android.providers.tv" to "TV Channels Storage Provider",
        "com.android.providers.contacts" to "Contacts Storage Provider",
        "com.android.providers.userdictionary" to "User Dictionary Provider",

        // Networking, Wi-Fi, Bluetooth & TV Input / HDMI-CEC
        "com.android.bluetooth" to "Bluetooth Pairing & Remote Control Transport",
        "com.google.android.bluetooth" to "Google Bluetooth Service",
        "com.android.bluetoothmidiservice" to "Bluetooth MIDI Service",
        "com.xiaomi.bluetooth" to "Xiaomi Bluetooth Service",
        "com.xiaomi.mitv.bluetooth" to "Xiaomi TV Bluetooth Pairing Service",
        "com.android.networkstack" to "Android Networking Stack",
        "com.google.android.networkstack" to "Google Networking Stack",
        "com.android.networkstack.tethering" to "Android Network Tethering Stack",
        "com.google.android.networkstack.tethering" to "Google Network Tethering Stack",
        "com.android.wifi" to "Android Wi-Fi Stack",
        "com.google.android.wifi" to "Google Wi-Fi Stack",
        "com.android.wifi.resources" to "Android Wi-Fi Resources",
        "com.google.android.wifi.resources" to "Google Wi-Fi Resources",
        "com.android.connectivity.resources" to "Android Connectivity Resources",
        "com.google.android.connectivity.resources" to "Google Connectivity Resources",
        "com.android.tv" to "Live TV Framework",
        "com.android.tv.tuner" to "Android TV Tuner Service",
        "com.google.android.tv.tuner" to "Google TV Tuner Service",
        "com.android.tv.inputservice" to "Android TV Input Service",
        "com.droidlogic.tvinput" to "Amlogic/Droidlogic TV Input Framework",
        "com.droidlogic.hdmicec" to "Amlogic/Droidlogic HDMI-CEC Service",
        "com.xiaomi.mitv.tvinput" to "Xiaomi TV Input Service",
        "com.xiaomi.mitv.hdmicec" to "Xiaomi TV HDMI-CEC Service",

        // Google Play Services, App Store & WebView (recovery-critical)
        "com.android.vending" to "Google Play Store (required for app updates & recovery)",
        "com.google.android.gms" to "Google Play Services",
        "com.google.android.gsf" to "Google Services Framework",
        "com.google.android.gsf.login" to "Google Account Login Framework",
        "com.google.android.webview" to "Android System WebView",
        "com.android.webview" to "Android System WebView",

        // Keyboards / Input & Remote-Control Services
        "com.android.inputdevices" to "Hardware Input Device Keymaps",
        "com.google.android.inputmethod.latin" to "Gboard On-Screen TV Keyboard",
        "com.android.inputmethod.latin" to "AOSP Keyboard",
        "com.google.android.leanback.ime" to "Android TV Leanback Keyboard",
        "com.xiaomi.mitv.ime" to "Xiaomi TV Input Method",
        "com.google.android.tv.remote.service" to "Android TV Remote Service v2 (disabling disconnects MoteTV!)",
        "com.xiaomi.mitv.remotecontroller" to "Xiaomi Remote Control Service",
        "com.xiaomi.ble.remotecontrol" to "Xiaomi BLE Remote Control Driver",

        // Accessibility Services (critical for user accessibility & recovery)
        "com.google.android.marvin.talkback" to "Android Accessibility Suite / TalkBack",
        "com.android.talkback" to "TalkBack Accessibility Service",
        "com.google.android.accessibility.switchaccess" to "Switch Access Accessibility Service",
        "com.android.accessibility.switchaccess" to "AOSP Switch Access Accessibility Service",

        // Core TV Customization
        "com.google.android.atv.customization" to "Android TV Core Customization"
    )

    val KNOWN_PACKAGES: List<KnownPackageDescriptor> = listOf(
        // 1. Manufacturer Telemetry & Analytics
        KnownPackageDescriptor(
            packageName = "com.miui.tv.analytics",
            appName = "Mi TV Analytics",
            category = PackageCategory.MANUFACTURER_TELEMETRY,
            recommendation = DebloatRecommendation.SAFE_TO_DISABLE,
            reason = "Xiaomi usage analytics and telemetry reporter.",
            riskLevel = RiskLevel.LOW,
            verifiedXiaomiStickModels = VERIFIED_XIAOMI_STICK_IDENTIFIERS,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.xiaomi.statistic",
            appName = "Xiaomi Statistics Service",
            category = PackageCategory.MANUFACTURER_TELEMETRY,
            recommendation = DebloatRecommendation.SAFE_TO_DISABLE,
            reason = "Background telemetry & diagnostic metrics collector.",
            riskLevel = RiskLevel.LOW,
            verifiedXiaomiStickModels = VERIFIED_XIAOMI_STICK_IDENTIFIERS,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.xiaomi.mitv.tvmanager",
            appName = "Mi TV Background Manager",
            category = PackageCategory.MANUFACTURER_TELEMETRY,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Xiaomi background telemetry/system helper. Review carefully on older Mi TV Sticks.",
            riskLevel = RiskLevel.MODERATE,
            requiresOptionalPrompt = true,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.sony.dtv.osat.analytics",
            appName = "Sony TV Analytics",
            category = PackageCategory.MANUFACTURER_TELEMETRY,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Sony Bravia analytics telemetry service (not a Xiaomi Mi TV Stick component).",
            riskLevel = RiskLevel.LOW,
            requiresOptionalPrompt = true,
            supportedManufacturers = setOf("sony")
        ),
        KnownPackageDescriptor(
            packageName = "com.tcl.telemetry",
            appName = "TCL Telemetry",
            category = PackageCategory.MANUFACTURER_TELEMETRY,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "TCL background analytics reporter (not a Xiaomi Mi TV Stick component).",
            riskLevel = RiskLevel.LOW,
            requiresOptionalPrompt = true,
            supportedManufacturers = setOf("tcl")
        ),

        // 2. Manufacturer Ads & Recommendation Services
        KnownPackageDescriptor(
            packageName = "com.mitv.tvhome.atv",
            appName = "Xiaomi PatchWall / Recommendations",
            category = PackageCategory.MANUFACTURER_ADS,
            recommendation = DebloatRecommendation.SAFE_TO_DISABLE,
            reason = "Xiaomi promoted home banner & recommendation feed service.",
            riskLevel = RiskLevel.LOW,
            verifiedXiaomiStickModels = VERIFIED_XIAOMI_STICK_IDENTIFIERS,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.mitv.tvhome.michannel",
            appName = "Mi Channel Ads & Feed",
            category = PackageCategory.MANUFACTURER_ADS,
            recommendation = DebloatRecommendation.SAFE_TO_DISABLE,
            reason = "Xiaomi promotional channel shelf and ad cards.",
            riskLevel = RiskLevel.LOW,
            verifiedXiaomiStickModels = VERIFIED_XIAOMI_STICK_IDENTIFIERS,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.mitv.tvhome.mitvplus",
            appName = "Mi TV+ Sponsored Channels",
            category = PackageCategory.MANUFACTURER_ADS,
            recommendation = DebloatRecommendation.SAFE_TO_DISABLE,
            reason = "Ad-supported Xiaomi FAST channels integration.",
            riskLevel = RiskLevel.LOW,
            verifiedXiaomiStickModels = VERIFIED_XIAOMI_STICK_IDENTIFIERS,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.xm.webcontent",
            appName = "Xiaomi Web Promo Content",
            category = PackageCategory.MANUFACTURER_ADS,
            recommendation = DebloatRecommendation.SAFE_TO_DISABLE,
            reason = "Loads web-based promotional banners in stock launcher.",
            riskLevel = RiskLevel.LOW,
            verifiedXiaomiStickModels = VERIFIED_XIAOMI_STICK_IDENTIFIERS,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.google.android.tvrecommendations",
            appName = "Android TV Home Recommendations",
            category = PackageCategory.MANUFACTURER_ADS,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Populates sponsored recommendation rows in Google TV/Android TV Home.",
            riskLevel = RiskLevel.MODERATE,
            requiresOptionalPrompt = true
        ),

        // 3. Manufacturer Content Providers
        KnownPackageDescriptor(
            packageName = "com.xiaomi.floatingframe",
            appName = "Xiaomi Floating Promo Overlay",
            category = PackageCategory.MANUFACTURER_CONTENT,
            recommendation = DebloatRecommendation.SAFE_TO_DISABLE,
            reason = "Floating promotional frame overlay service.",
            riskLevel = RiskLevel.LOW,
            verifiedXiaomiStickModels = VERIFIED_XIAOMI_STICK_IDENTIFIERS,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.xiaomi.mitv.smartshare",
            appName = "Xiaomi SmartShare",
            category = PackageCategory.MANUFACTURER_CONTENT,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Xiaomi proprietary screen/content sharing service.",
            riskLevel = RiskLevel.MODERATE,
            requiresOptionalPrompt = true,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.mitv.milinkservice",
            appName = "MiLink Media Service",
            category = PackageCategory.MANUFACTURER_CONTENT,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Xiaomi proprietary casting companion; unnecessary if using Google Cast.",
            riskLevel = RiskLevel.MODERATE,
            requiresOptionalPrompt = true,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.mitv.dream",
            appName = "Xiaomi Gallery Screensaver",
            category = PackageCategory.MANUFACTURER_CONTENT,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Manufacturer ambient screensaver provider.",
            riskLevel = RiskLevel.LOW,
            requiresOptionalPrompt = true,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.mitv.tvhome",
            appName = "Xiaomi Stock TV Launcher",
            category = PackageCategory.SYSTEM_ESSENTIAL,
            recommendation = DebloatRecommendation.NEVER_DISABLE,
            reason = "Stock Xiaomi HOME launcher. Permanently protected so the TV always keeps a fallback HOME launcher.",
            riskLevel = RiskLevel.CRITICAL_BLOCKED,
            requiresOptionalPrompt = false,
            supportedManufacturers = setOf("xiaomi")
        ),

        // 4. Optional Preinstalled Apps (incl. Firmware Updates & Assistant -> ask user!)
        KnownPackageDescriptor(
            packageName = "com.xiaomi.mitv.updateservice",
            appName = "Xiaomi Firmware Updater",
            category = PackageCategory.OPTIONAL_PREINSTALLED,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Delivers Xiaomi OS security and firmware OTA updates. Ask user before disabling.",
            riskLevel = RiskLevel.MODERATE,
            requiresOptionalPrompt = true,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.xiaomi.amazon.alexa",
            appName = "Amazon Alexa Preload",
            category = PackageCategory.OPTIONAL_PREINSTALLED,
            recommendation = DebloatRecommendation.SAFE_TO_DISABLE,
            reason = "Preinstalled third-party Alexa voice client.",
            riskLevel = RiskLevel.LOW,
            verifiedXiaomiStickModels = VERIFIED_XIAOMI_STICK_IDENTIFIERS,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "android.autoinstalls.config.xiaomi.amelie",
            appName = "Xiaomi PAI Auto-Installer Config",
            category = PackageCategory.OPTIONAL_PREINSTALLED,
            recommendation = DebloatRecommendation.SAFE_TO_DISABLE,
            reason = "Play Auto Installs configuration stub for preloaded apps.",
            riskLevel = RiskLevel.LOW,
            verifiedXiaomiStickModels = VERIFIED_XIAOMI_STICK_IDENTIFIERS,
            supportedManufacturers = setOf("xiaomi")
        ),

        // 5. Streaming Apps (ask user — never auto-disable Netflix/YouTube!)
        KnownPackageDescriptor(
            packageName = "com.netflix.ninja",
            appName = "Netflix",
            category = PackageCategory.STREAMING,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Streaming app — do not disable unless you never use Netflix.",
            riskLevel = RiskLevel.MODERATE,
            requiresOptionalPrompt = true
        ),
        KnownPackageDescriptor(
            packageName = "com.google.android.youtube.tv",
            appName = "YouTube for Android TV",
            category = PackageCategory.STREAMING,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Primary YouTube app — ask user before changing.",
            riskLevel = RiskLevel.MODERATE,
            requiresOptionalPrompt = true
        ),
        KnownPackageDescriptor(
            packageName = "com.amazon.amazonvideo.livingroom",
            appName = "Prime Video",
            category = PackageCategory.STREAMING,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Preinstalled streaming client.",
            riskLevel = RiskLevel.LOW,
            requiresOptionalPrompt = true
        ),
        KnownPackageDescriptor(
            packageName = "com.disney.disneyplus",
            appName = "Disney+",
            category = PackageCategory.STREAMING,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Preinstalled streaming client.",
            riskLevel = RiskLevel.LOW,
            requiresOptionalPrompt = true
        ),

        // 6. Google Services (Chromecast, Voice Assistant -> ask user; Stock Launchers & Setup -> NEVER_DISABLE!)
        KnownPackageDescriptor(
            packageName = "com.google.android.apps.mediashell",
            appName = "Chromecast Built-in",
            category = PackageCategory.GOOGLE_SERVICES,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Powers Google Cast / Chromecast built-in; disabling breaks casting from phones.",
            riskLevel = RiskLevel.HIGH,
            requiresOptionalPrompt = true
        ),
        KnownPackageDescriptor(
            packageName = "com.google.android.katniss",
            appName = "Google Assistant for TV",
            category = PackageCategory.GOOGLE_SERVICES,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Voice search and Assistant key on remote. Disabling disables microphone search.",
            riskLevel = RiskLevel.MODERATE,
            requiresOptionalPrompt = true
        ),
        KnownPackageDescriptor(
            packageName = "com.google.android.tvlauncher",
            appName = "Android TV Home (Stock Launcher)",
            category = PackageCategory.SYSTEM_ESSENTIAL,
            recommendation = DebloatRecommendation.NEVER_DISABLE,
            reason = "Stock Android TV launcher. Permanently protected so the TV always keeps a fallback HOME launcher.",
            riskLevel = RiskLevel.CRITICAL_BLOCKED,
            requiresOptionalPrompt = false
        ),
        KnownPackageDescriptor(
            packageName = "com.google.android.apps.tv.launcherx",
            appName = "Google TV Home (Stock Launcher)",
            category = PackageCategory.SYSTEM_ESSENTIAL,
            recommendation = DebloatRecommendation.NEVER_DISABLE,
            reason = "Stock Google TV launcher. Permanently protected so the TV always keeps a fallback HOME launcher.",
            riskLevel = RiskLevel.CRITICAL_BLOCKED,
            requiresOptionalPrompt = false
        ),
        KnownPackageDescriptor(
            packageName = "com.google.android.backdrop",
            appName = "Ambient Mode Screensaver",
            category = PackageCategory.GOOGLE_SERVICES,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Google Ambient screensaver & photo frames.",
            riskLevel = RiskLevel.LOW,
            requiresOptionalPrompt = true
        ),
        KnownPackageDescriptor(
            packageName = "com.google.android.partnersetup",
            appName = "Google Partner Setup",
            category = PackageCategory.SYSTEM_ESSENTIAL,
            recommendation = DebloatRecommendation.NEVER_DISABLE,
            reason = "Provisioning & partner customization helper.",
            riskLevel = RiskLevel.CRITICAL_BLOCKED,
            requiresOptionalPrompt = false
        )
    )

    val KNOWN_BY_PACKAGE: Map<String, KnownPackageDescriptor> =
        KNOWN_PACKAGES.associateBy { it.packageName.lowercase() }
}
