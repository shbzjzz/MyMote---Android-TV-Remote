# MoteTV Android TV Debloat Safety Architecture

## Incident Background (CleanYourTV Issue #884)

In [Gathin23/cleanyourtv#884](https://github.com/Gathin23/cleanyourtv/issues/884), disabling the Xiaomi Mi TV Stick stock launcher, Android TV Setup Wizard (`com.google.android.tungsten.setupwraith`), and core Android system packages caused devices to hang permanently on the Android TV boot screen.

Core lessons enforced throughout MoteTV:
1. **Absence from a bloatware list does not make a package safe.**
2. **Installing/activating an alternative HOME launcher does not make disabling the original stock launcher safe.**
3. **Safety checks must live at the lowest execution boundary (`AdbConnectionManagerImpl`), not just in UI views.**

---

## Non-Bypassable Enforcement Architecture

Every disable path passes through `PackageSafetyGuard`:
- **Scanner & UI Selection (`TvPackageScanner`, `DebloatManager.togglePackageSelection`)**: Hard-blocked, stock-launcher, and unknown system packages are labeled `NEVER_DISABLE` / `RiskLevel.CRITICAL_BLOCKED` and cannot be selected.
- **Recommended Safe Selection (`DebloatManager.selectRecommendedSafePackages`)**: Only selects catalog-verified `SAFE_TO_DISABLE` entries when exact Xiaomi Mi TV Stick device metadata matches (`VERIFIED_XIAOMI_STICK_IDENTIFIERS`).
- **Imported Lists (`DebloatManager.importPackageListForReview`)**: Rejects any list containing a blocked, stock-launcher, or unknown system package.
- **Debloat Orchestrator (`DebloatManager.disablePackagesWithBackup`)**: Reconciles interrupted operations first and validates every target through `PackageSafetyGuard.evaluateDisableCandidate` before creating a backup or touching ADB.
- **ADB Execution Boundary (`AdbConnectionManagerImpl.disablePackageForUser` & `runValidatedPmCommand`)**: Independently enforces `PackageSafetyGuard.checkExecutionBoundaryBlock` before any `pm disable`, `pm disable-user`, `pm hide`, `pm suspend`, or `pm uninstall` shell command is transmitted to the TV. Zero shell commands are sent if blocked. No force flag, confirmation dialog, developer mode, or active-HOME override can bypass a hard-blocked package.

---

## Permanent Hard-Blocked Packages & Families

Defined centrally in `CleanYourTvPackageCatalog` and `PackageSafetyGuard`:

1. **Permission & Package Management**
   - `com.google.android.permissioncontroller`, `com.android.permissioncontroller`, `android.permissioncontroller`
   - `com.google.android.packageinstaller`, `com.android.packageinstaller`, `android.packageinstaller`
2. **Extension Services, Module Metadata & CTS Shims**
   - `com.google.android.ext.services`, `com.android.ext.services`, `android.ext.services`
   - `com.google.android.ext.shared`, `com.android.ext.shared`, `android.ext.shared`
   - `com.google.android.modulemetadata`, `com.android.modulemetadata`
   - `com.android.cts.priv.ctsshim`, `com.android.cts.ctsshim`, `com.android.apex.cts.shim`
3. **Runtime Resource Overlay Modules (Prefix & Namespace-Family Matching)**
   - `com.google.android.overlay.modules.*`, `com.google.android.overlay.*`, `com.android.overlay.*`, `android.overlay.*`, `com.android.theme.*`, `com.android.internal.*`
   - Overlay sub-packages within trusted Android/OEM system namespaces (e.g., `com.xiaomi.mitv.overlay.*`)
4. **Setup & Provisioning**
   - `com.google.android.tungsten.setupwraith`
   - `com.google.android.setupwizard`, `com.android.setupwizard`, `com.android.provision`, `com.google.android.partnersetup`, `com.xiaomi.mitv.setup`
5. **Original Stock HOME Launchers (Always Protected)**
   - `com.google.android.tvlauncher`, `com.google.android.apps.tv.launcherx`, `com.mitv.tvhome`, `com.android.tv.launcher`, OEM stock launchers, and whichever package currently resolves as active `HOME`.
   - Always kept enabled as a guaranteed fallback HOME launcher even when CleanYourTV Home (`com.gathin.tvhome`) is active.
6. **Core Android TV Platform & Hardware Services**
   - `android`, `com.android.systemui`, `com.android.settings`, `com.android.tv.settings`, `com.android.shell`, `com.android.providers.*`, `com.android.networkstack`, `com.google.android.networkstack`, Wi-Fi, Bluetooth, IME/keyboards, Accessibility (`talkback`, `switchaccess`), Remote/CEC/Tuner HALs, `com.google.android.gms`, `com.google.android.gsf`, `com.android.vending`, `com.google.android.webview`.
7. **Scoped Non-False-Positive Matching**
   - Segment-level rules (`overlay`, `tvinput`, `hdmicec`, etc.) are evaluated only inside trusted system/OEM namespaces (`SYSTEM_NAMESPACES`) so unrelated third-party apps (e.g., `com.example.hdmichecker`) remain unaffected.

---

## Unknown Package Fail-Closed Policy

- During `scanTvPackages()`, `AdbConnectionManager.listSystemPackages()` runs `pm list packages -s`.
- Any unknown package in Android/AOSP/Google/OEM system namespaces OR reported by `pm list packages -s` defaults to `DebloatRecommendation.NEVER_DISABLE` and `RiskLevel.CRITICAL_BLOCKED` (cannot be selected or disabled).
- Unknown third-party user applications default to `DebloatRecommendation.OPTIONAL_ASK_USER` with an explicit warning prompt and are never auto-selected.

---

## Crash-Safe Write-Ahead Log (WAL) & Recovery Protocol

`DebloatManager.disablePackagesWithBackup()` executes a 6-step crash-safe sequence per package after durable backup verification:
1. Persist and read-back-verify a `PendingDebloatOperation` (`operationId`, `backupId`, `packageName`, `wasOriginallyDisabled`, device identity, `PENDING_DISABLE`).
2. Execute `adbManager.disablePackageForUser(pkg)`.
3. Verify actual TV state via `adbManager.listDisabledPackages()`.
4. If `!wasOriginallyDisabled`, persist `repository.markPackageModified(pkg)` with `.commit()` + read-back verification.
   - **Immediate Rollback on Failure**: If tracking persistence or verification fails after TV disabled the package, `DebloatManager` marks the pending operation `ROLLBACK_REQUIRED`, runs `adbManager.enablePackage(pkg)`, verifies it is enabled on TV, logs the exact failure, and halts all remaining packages. If rollback fails, the `ROLLBACK_REQUIRED` record is retained and future debloats are blocked until recovery runs.
5. Clear the `PendingDebloatOperation` record ONLY after all persistence checks succeed.
6. `TvRecoveryCoordinator.recoverTv()` reconciles `modifiedPackages`, unresolved `pendingOperations`, and disabled `(backup.intendedDisablePackages - backup.originallyDisabledPackages)` targets across app restarts while **never** re-enabling packages recorded in `originallyDisabledPackages`.

---

## Original HOME Launcher Recovery Guarantees

`CleanYourTvLauncherManager` enforces:
1. Original non-CleanYourTV HOME package and component are durably committed and read-back verified **before** calling `setDefaultHomeActivity(com.gathin.tvhome/.MainActivity)`. If persistence or read-back fails, HOME switching is aborted.
2. Original stock launchers are never disabled.
3. `restoreOriginalHomeLauncher()` runs `pm enable`, verifies the package is installed and enabled (`pm list packages` + `pm list packages -d`), sets the default HOME activity, and verifies resolved `HOME` matches the saved original launcher.
4. Recovery records are preserved until restoration is verified so retries remain safe across disconnects and restarts.
