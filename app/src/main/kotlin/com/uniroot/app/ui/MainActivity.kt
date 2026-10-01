package com.uniroot.app.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.uniroot.app.R
import com.uniroot.app.engine.DeviceProfile
import com.uniroot.app.engine.InstantRoot
import com.uniroot.app.engine.RunLogFile
import com.uniroot.app.engine.RootEngine
import com.uniroot.app.newmethod.KsudNextProfile
import com.uniroot.app.newmethod.KsudClassicProfiles
import com.uniroot.app.newmethod.KsudNextProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : ComponentActivity(), Shizuku.OnRequestPermissionResultListener {

    private lateinit var engine: RootEngine

    private var deviceName by mutableStateOf("")
    private var socName by mutableStateOf("")
    private var kernelRelease by mutableStateOf("")
    private var deviceInfoRows by mutableStateOf(listOf<Pair<String, String>>())
    private var rootStateRow by mutableStateOf("")
    private var rootDetailRow by mutableStateOf("")
    private var selinuxRow by mutableStateOf("")
    private var autoMatched by mutableStateOf(false)
    private var advancedVisible by mutableStateOf(false)
    private var shizukuEnabled by mutableStateOf(false)
    private var profiles by mutableStateOf(listOf<DeviceProfile>())
    private var selectedProfileName by mutableStateOf<String?>(null)
    private var sheetVisible by mutableStateOf(false)
    private var sheetDismissible by mutableStateOf(false)
    private var permissionTick by mutableIntStateOf(0)

    private var useLatestKsu by mutableStateOf(false)
    private var latestKsuTag by mutableStateOf("")
    private var autoRootOnBoot by mutableStateOf(false)
    private var ksuNextMode by mutableStateOf(false)
    private var rmgStatus by mutableStateOf("")
    private var tab by mutableStateOf(UniTab.UNIROOT)
    private var runLogs by mutableStateOf(listOf<RunLogFile>())
    private var logViewerFile by mutableStateOf<RunLogFile?>(null)
    private var logViewerContent by mutableStateOf("")
    private var instantRootEnabled by mutableStateOf(false)
    private var instantRootInstalled by mutableStateOf(false)
    private var instantRootOfferVisible by mutableStateOf(false)
    private var keepInstantRootVisible by mutableStateOf(false)
    private var instantRootInstallVisible by mutableStateOf(false)
    private var instantRootLogVisible by mutableStateOf(false)
    private var instantRootLogContent by mutableStateOf("")
    private var superuserNeededVisible by mutableStateOf(false)
    private var injectConfirmVisible by mutableStateOf(false)
    private var newMethod by mutableStateOf(false)
    private var newMethodBootEnabled by mutableStateOf(false)
    private var newMethodSoftReboot by mutableStateOf(true)
    private var newMethodProgress by mutableIntStateOf(0)
    private var newMethodStage by mutableStateOf("")
    // New méthode + KernelSU Next : profils ksud embarqués (3.3.0, 3.4.0…).
    private var ksudNextProfiles by mutableStateOf<List<KsudNextProfile>>(emptyList())
    private var ksudNextProfileId by mutableStateOf("")
    private var ksudClassicProfiles by mutableStateOf<List<KsudClassicProfiles.ClassicProfile>>(emptyList())
    private var ksudClassicProfileId by mutableStateOf("")
    // DEV — updater ksud Next (blue button, new method section).
    private var ksudUpdateRunning by mutableStateOf(false)
    private var ksudUpdateLog by mutableStateOf("")
    private var ksudUpdateSheetVisible by mutableStateOf(false)
    private var ksudUpdateProgress by mutableIntStateOf(0)
    private var ksudUpdateStage by mutableStateOf("")
    private var pendingInstant: InstantRoot? = null
    private var pendingProfileName by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = RootEngine(applicationContext)
        engine.initialize()
        Shizuku.addRequestPermissionResultListener(this)
        useLatestKsu = engine.useLatestKsu
        latestKsuTag = engine.latestKsuTag()
        autoRootOnBoot = engine.autoRootOnBoot
        ksuNextMode = engine.ksuFlavor == "kernelsu_next"
        // New méthode : profils ksud Next embarqués + sélection persistée.
        ksudNextProfiles = KsudNextProfiles.list(this)
        ksudNextProfileId = KsudNextProfiles.selected(this)?.id ?: ""
        ksudClassicProfiles = KsudClassicProfiles.list(this)
        ksudClassicProfileId = KsudClassicProfiles.selected(this)?.id ?: ""
        // New method state: pref + boot-receiver component state + DE prefs.
        newMethod = getSharedPreferences("uniroot_prefs", MODE_PRIVATE)
            .getBoolean("root_method_new", false)
        newMethodBootEnabled = runCatching {
            packageManager.getComponentEnabledSetting(
                android.content.ComponentName(
                    this, com.uniroot.app.newmethod.NewMethodBootReceiver::class.java)
            ) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }.getOrDefault(false)
        newMethodSoftReboot = createDeviceProtectedStorageContext()
            .getSharedPreferences(com.uniroot.app.newmethod.NewMethodBootReceiver.PREFS, MODE_PRIVATE)
            .getBoolean(com.uniroot.app.newmethod.NewMethodBootReceiver.PREF_AUTO_SOFT_REBOOT, true)
        runLogs = engine.listRunLogs()
        refreshInstantRootState()
        refreshDevice()
        refreshDeviceInfoPanel()
        refreshProfiles()
        checkRootMyGalaxy(silent = true)
        setContent {
            @Suppress("UNUSED_EXPRESSION") permissionTick
            val context = LocalContext.current
            val rooted by engine.rooted.collectAsState()
            val logs by engine.logLines.collectAsState()
            val running by engine.running.collectAsState()
            UniApp(
                state = UniUiState(
                    deviceName = deviceName,
                    socName = socName,
                    kernelRelease = kernelRelease,
                    deviceInfoRows = deviceInfoRows,
                    rootStateRow = rootStateRow,
                    rootDetailRow = rootDetailRow,
                    selinuxRow = selinuxRow,
                    kernelSupported = selectedProfileName != null,
                    rooted = rooted,
                    running = running,
                    advancedVisible = advancedVisible,
                    shizukuEnabled = shizukuEnabled,
                    profiles = profiles,
                    selectedProfileName = selectedProfileName,
                    executionSheetVisible = sheetVisible,
                    executionSheetDismissible = sheetDismissible,
                    logLines = logs,
                    useLatestKsu = useLatestKsu,
                    latestKsuTag = latestKsuTag,
                    autoRootOnBoot = autoRootOnBoot,
                    rmgStatus = rmgStatus,
                    ksuNextMode = ksuNextMode,
                    tab = tab,
                    runLogs = runLogs,
                    logViewerFile = logViewerFile,
                    logViewerContent = logViewerContent,
                    instantRootEnabled = instantRootEnabled,
                    instantRootInstalled = instantRootInstalled,
                    instantRootOfferVisible = instantRootOfferVisible,
                    keepInstantRootVisible = keepInstantRootVisible,
                    instantRootInstallVisible = instantRootInstallVisible,
                    instantRootLogVisible = instantRootLogVisible,
                    instantRootLogContent = instantRootLogContent,
                    superuserNeededVisible = superuserNeededVisible,
                    injectConfirmVisible = injectConfirmVisible,
                    newMethod = newMethod,
                    newMethodBootEnabled = newMethodBootEnabled,
                    newMethodAutoSoftReboot = newMethodSoftReboot,
                    newMethodProgress = newMethodProgress,
                    newMethodStage = newMethodStage,
                    ksudNextProfiles = ksudNextProfiles,
                    ksudNextProfileId = ksudNextProfileId,
                    ksudClassicProfiles = ksudClassicProfiles,
                    ksudClassicProfileId = ksudClassicProfileId,
                    ksudUpdateRunning = ksudUpdateRunning,
                    ksudUpdateLog = ksudUpdateLog,
                    ksudUpdateSheetVisible = ksudUpdateSheetVisible,
                    ksudUpdateProgress = ksudUpdateProgress,
                    ksudUpdateStage = ksudUpdateStage,
                ),
                actions = object : UniActions {
                    override fun onRun() { if (newMethod) launchNewMethod() else launchRun() }
                    override fun onRootMethodChanged(isNew: Boolean) {
                        newMethod = isNew
                        getSharedPreferences("uniroot_prefs", MODE_PRIVATE)
                            .edit().putBoolean("root_method_new", isNew).apply()
                        if (isNew) engine.appendLog("[NewMethod] DirtyFrag engine — no profile needed")
                    }
                    override fun onNewMethodBootChanged(enabled: Boolean) {
                        newMethodBootEnabled = enabled
                        syncBootReceiverEnabled()
                    }
                    override fun onNewMethodSoftRebootChanged(enabled: Boolean) {
                        newMethodSoftReboot = enabled
                        createDeviceProtectedStorageContext()
                            .getSharedPreferences(com.uniroot.app.newmethod.NewMethodBootReceiver.PREFS, MODE_PRIVATE)
                            .edit()
                            .putBoolean(com.uniroot.app.newmethod.NewMethodBootReceiver.PREF_AUTO_SOFT_REBOOT, enabled)
                            .apply()
                    }
                    override fun onKsudNextProfileChanged(id: String) {
                        ksudNextProfileId = id
                        engine.ksudNextProfile = id
                        val label = ksudNextProfiles.firstOrNull { it.id == id }?.label ?: id
                        engine.appendLog("[NewMethod] profil ksud Next : $label")
                    }
                    override fun onUpdateKsud() {
                        if (ksudUpdateRunning) return
                        ksudUpdateRunning = true
                        ksudUpdateLog = ""
                        ksudUpdateProgress = 0
                        ksudUpdateStage = ""
                        ksudUpdateSheetVisible = true
                        lifecycleScope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    com.uniroot.app.newmethod.DfKsudUpdater.run(
                                        this@MainActivity,
                                        !ksuNextMode,
                                        { line ->
                                            ksudUpdateLog += line + "\n"
                                            engine.appendLog("[KsudUpdate] $line")
                                        },
                                        { pct, stage ->
                                            ksudUpdateProgress = pct
                                            if (stage.isNotEmpty()) ksudUpdateStage = stage
                                        })
                                }
                            }.getOrElse { "ERROR: ${it.message}" }
                            ksudUpdateLog += "\n$result"
                            ksudUpdateStage = result
                            ksudUpdateProgress = 100
                            // The result lands in the selector, auto-selected.
                            ksudNextProfiles = KsudNextProfiles.list(this@MainActivity)
                            ksudNextProfileId = KsudNextProfiles.selected(this@MainActivity)?.id ?: ""
                            engine.ksudNextProfile = ksudNextProfileId
                            ksudClassicProfiles = KsudClassicProfiles.list(this@MainActivity)
                            ksudClassicProfileId = KsudClassicProfiles.selected(this@MainActivity)?.id ?: ""
                            ksudUpdateRunning = false
                        }
                    }
                    override fun onCloseKsudUpdateSheet() {
                        if (!ksudUpdateRunning) ksudUpdateSheetVisible = false
                    }
                    override fun onDeleteKsudProfile(id: String, classic: Boolean) {
                        if (classic) {
                            if (!KsudClassicProfiles.deleteLatest(this@MainActivity)) return
                            engine.appendLog("[KsudUpdate] classic profile deleted: $id")
                            ksudClassicProfiles = KsudClassicProfiles.list(this@MainActivity)
                            ksudClassicProfileId = KsudClassicProfiles.selected(this@MainActivity)?.id ?: ""
                        } else {
                            if (!KsudNextProfiles.deleteDynamic(this@MainActivity, id)) return
                            engine.appendLog("[KsudUpdate] profile deleted: $id")
                            ksudNextProfiles = KsudNextProfiles.list(this@MainActivity)
                            ksudNextProfileId = KsudNextProfiles.selected(this@MainActivity)?.id ?: ""
                            engine.ksudNextProfile = ksudNextProfileId
                        }
                    }
                    override fun onKsudClassicProfileChanged(id: String) {
                        ksudClassicProfileId = id
                        KsudClassicProfiles.setSelected(this@MainActivity, id)
                        engine.appendLog("[NewMethod] profil ksud classic : $id")
                    }
                    override fun onDisableRoot() = disableRoot()
                    override fun onInstantRootEnable() { instantRootOfferVisible = false; enableInstantRoot() }
                    override fun onInstantRootNotNow() {
                        instantRootOfferVisible = false
                        engine.instantRootPrompted = true
                        // Popup resolved — only NOW do the delayed manager restart.
                        lifecycleScope.launch { delay(12_000); restartKsuManager() }
                    }
                    override fun onKeepInstantRoot(keep: Boolean) {
                        keepInstantRootVisible = false
                        pendingProfileName?.let { selectedProfileName = it }
                        autoMatched = false
                        pendingProfileName = null
                        if (!keep) disableInstantRoot()
                    }
                    override fun onInstantRootEnabledChanged(enabled: Boolean) = applyInstantRootEnabled(enabled)
                    override fun onUninstallHelper() = uninstallHelper()
                    override fun onInstantRootInstallNow() {
                        instantRootInstallVisible = false
                        val intent = InstantRoot(this@MainActivity).helperInstallIntent()
                        if (intent != null) {
                            runCatching { startActivity(intent) }
                                .onFailure { Toast.makeText(this@MainActivity, R.string.instant_root_install_failed, Toast.LENGTH_LONG).show() }
                        } else {
                            Toast.makeText(this@MainActivity, R.string.instant_root_install_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                    override fun onInstantRootInstallLater() { instantRootInstallVisible = false }
                    override fun onInjectContinue() {
                        injectConfirmVisible = false
                        continueInstantRootSetup()
                    }
                    override fun onOpenHelper() {
                        runCatching {
                            startActivity(
                                Intent().setClassName(
                                    InstantRoot.PKG_REROOT,
                                    InstantRoot.PKG_REROOT + ".MainActivity",
                                ).putExtra("manual", true)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure {
                            Toast.makeText(this@MainActivity, R.string.instant_root_need_root, Toast.LENGTH_SHORT).show()
                        }
                    }
                    override fun onInjectCancel() {
                        injectConfirmVisible = false
                        pendingInstant?.record("[InstantRoot] Cancelled before injection.")
                        pendingInstant = null
                    }
                    override fun onOpenKsuManager() {
                        superuserNeededVisible = false
                        restartKsuManager()
                    }
                    override fun onDismissSuperuserNeeded() { superuserNeededVisible = false }
                    override fun onOpenInstantRootLog() {
                        instantRootLogVisible = true
                        instantRootLogContent = "Loading…"
                        lifecycleScope.launch {
                            val instant = InstantRoot(this@MainActivity)
                            val sb = StringBuilder(
                                instant.readLog().ifBlank { "No instant root log yet — press Enable first." }
                            )
                            val helperBootLog = withContext(Dispatchers.IO) {
                                instant.su(
                                    "cat /storage/emulated/0/Android/data/" +
                                        InstantRoot.PKG_REROOT + "/files/autoroot.log",
                                    timeoutSec = 20,
                                )?.second
                            }
                            if (!helperBootLog.isNullOrBlank()) {
                                sb.append("\n----- helper boot log (DFReroot) -----\n").append(helperBootLog)
                            }
                            val bootLog = withContext(Dispatchers.IO) {
                                instant.su("cat /data/system/dfreroot-boot.log", timeoutSec = 20)?.second
                            }
                            if (!bootLog.isNullOrBlank()) {
                                sb.append("\n----- /data/system/dfreroot-boot.log -----\n").append(bootLog)
                            }
                            instantRootLogContent = sb.toString()
                        }
                    }
                    override fun onCloseInstantRootLog() { instantRootLogVisible = false }
                    override fun onCloseExecutionSheet() { if (sheetDismissible) sheetVisible = false }
                    override fun onToggleAdvanced() { advancedVisible = !advancedVisible }
                    override fun onCopyLogs() = copyLogs()
                    override fun onTabSelected(newTab: UniTab) { tab = newTab }
                    override fun onProfileSelected(index: Int) {
                        val newName = profiles.getOrNull(index)?.name
                        if (newName == null || newName == selectedProfileName) return
                        if (instantRootEnabled) {
                            // Ask before silently keeping/cutting the boot re-root.
                            pendingProfileName = newName
                            keepInstantRootVisible = true
                        } else {
                            selectedProfileName = newName
                            autoMatched = false
                        }
                    }
                    override fun onShizukuChanged(enabled: Boolean) { shizukuEnabled = enabled }
                    override fun onKsuFlavorChanged(next: Boolean) {
                        ksuNextMode = next
                        engine.ksuFlavor = if (next) "kernelsu_next" else "kernelsu"
                        // The new method stages its ksud per flavor too.
                        createDeviceProtectedStorageContext()
                            .getSharedPreferences(com.uniroot.app.newmethod.NewMethodBootReceiver.PREFS, MODE_PRIVATE)
                            .edit()
                            .putBoolean(com.uniroot.app.newmethod.NewMethodBootReceiver.PREF_NEXT, next)
                            .apply()
                        refreshProfiles()
                    }
                    override fun onResetProfiles() = resetProfiles()
                    override fun onProfileSave(profile: DeviceProfile, originalName: String?) {
                        engine.addOrUpdateProfile(profile, originalName)
                        refreshProfiles()
                        selectedProfileName = profile.name
                        Toast.makeText(context, R.string.profile_saved, Toast.LENGTH_SHORT).show()
                    }
                    override fun onProfileDelete(name: String) {
                        engine.deleteProfile(name)
                        refreshProfiles()
                        if (selectedProfileName == name) {
                            selectedProfileName = engine.detectDevice().matchedProfileName
                                ?.takeIf { engine.profileByName(it) != null }
                        }
                    }
                    override fun onUseLatestKsuChanged(enabled: Boolean) {
                        useLatestKsu = enabled
                        engine.useLatestKsu = enabled
                        if (enabled) {
                            Toast.makeText(context, R.string.use_latest_enabled_toast, Toast.LENGTH_LONG).show()
                        }
                    }
                    override fun onDownloadLatestKsu() = downloadLatestKsu()
                    override fun onAutoRootChanged(enabled: Boolean) {
                        // Exclusive with instant root: one or the other, never both.
                        if (enabled && instantRootEnabled) {
                            Toast.makeText(context, R.string.auto_root_exclusive, Toast.LENGTH_LONG).show()
                            return
                        }
                        autoRootOnBoot = enabled
                        engine.autoRootOnBoot = enabled
                        if (enabled && android.os.Build.VERSION.SDK_INT >= 33) {
                            runCatching {
                                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
                            }
                        }
                        // The "DON'T TOUCH THE PHONE" banner needs the overlay permission.
                        if (enabled && !android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                            runCatching {
                                startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:$packageName"),
                                    ),
                                )
                            }
                        }
                    }
                    override fun onCheckRmg() = checkRootMyGalaxy(silent = false)
                    override fun onRunLogOpen(file: RunLogFile) {
                        logViewerContent = runCatching { file.file.readText() }.getOrDefault("")
                        logViewerFile = file
                    }
                    override fun onRunLogShare(file: RunLogFile) = shareRunLog(file.file)
                    override fun onRunLogDelete(file: RunLogFile) {
                        engine.deleteRunLog(file.file)
                        runLogs = engine.listRunLogs()
                        if (logViewerFile == file) { logViewerFile = null; logViewerContent = "" }
                    }
                    override fun onRunLogViewerClose() { logViewerFile = null; logViewerContent = "" }
                },
            )
        }
        setupSystemBars()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(this)
    }

    override fun onResume() {
        super.onResume()
        // Landing after the setup soft reboot: prompt the helper install, or
        // complete the setup if the user just installed it.
        checkInstantRootLanding()
        maybeWakeHelper()
    }

    override fun onRequestPermissionResult(requestCode: Int, result: Int) {
        permissionTick++
    }

    private fun refreshDeviceInfoPanel() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                runCatching { com.uniroot.app.engine.DeviceInfoPanel.rows(this@MainActivity) }
                    .getOrDefault(emptyList())
            }
            // Keep the panel focused (user feedback: "too much info"):
            // the manager-style essentials only.
            val keep = setOf("Device", "Android", "Kernel")
            deviceInfoRows = rows.filter { it.first in keep }
            if (com.uniroot.app.BuildConfig.DIRTYFRAG_DIAGNOSTIC_ONLY) {
                rootStateRow = "Diagnostic"
                rootDetailRow = "Root probes disabled in diagnostic build"
                selinuxRow = ""
                return@launch
            }
            // Grant-independent root state + flavor line
            val (state, _, detail) = com.uniroot.app.engine.DeviceInfoPanel.rootState()
            val flavor = com.uniroot.app.engine.DeviceInfoPanel.flavor(this@MainActivity)
            rootStateRow = state
            rootDetailRow = detail + " · " + flavor
            // SELinux only with a su grant (getenforce needs root)
            selinuxRow = ""
            val se = withContext(Dispatchers.IO) {
                val inst = InstantRoot(this@MainActivity)
                if (inst.suId(timeoutSec = 4) != null) {
                    inst.su("getenforce", timeoutSec = 5)?.second?.trim()
                } else null
            }
            selinuxRow = se?.takeIf { it.isNotBlank() } ?: ""
        }
    }

    private fun refreshDevice() {
        val info = engine.detectDevice()
        deviceName = info.model
        socName = info.soc
        kernelRelease = info.kernel
        if (info.matchedProfileName != null && engine.profileByName(info.matchedProfileName) != null) {
            selectedProfileName = info.matchedProfileName
            autoMatched = true
        }
    }

    private fun refreshProfiles() {
        val flavor = if (ksuNextMode) "kernelsu_next" else "kernelsu"
        val suffix = if (ksuNextMode) " Next" else ""
        profiles = engine.profiles.filter { it.flavor == flavor }
        if (selectedProfileName == null || profiles.none { it.name == selectedProfileName }) {
            selectedProfileName = engine.detectDevice().matchedProfileName
                ?.let { matched -> engine.profileByName(matched + suffix)?.name ?: engine.profileByName(matched)?.name }
                ?: profiles.firstOrNull { it.name.startsWith("RMG · ") }?.name
        }
    }

    private fun checkRootMyGalaxy(silent: Boolean) {
        lifecycleScope.launch {
            rmgStatus = engine.checkRootMyGalaxy()
            refreshProfiles()
            if (!silent) Toast.makeText(this@MainActivity, rmgStatus, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * After a successful run: kill the (possibly stale) KernelSU manager so it
     * re-reads the freshly loaded module, then bring it back up.
     */
    private fun restartKsuManager() {
        // The relaunched manager follows the selected flavor: KernelSU Next
        // profiles ship real Next builds (S25: ksud-nxt-S938X, S26: ksud-nxt-S948X).
        val (installed, pkg) = engine.isKsuManagerInstalled(ksuNextMode)
        if (!installed) {
            Toast.makeText(this, R.string.manager_not_installed, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val stopped = engine.forceStopKsuManager(pkg)
            delay(if (stopped) 800L else 200L)
            // Shizuku `am start` always works (even with the app in background);
            // the launch-intent path is the fallback.
            val activity = if (pkg.startsWith("com.rifsxd")) {
                "com.rifsxd.ksunext.ui.MainActivity"
            } else {
                "me.weishu.kernelsu.ui.MainActivity"
            }
            val started = engine.startManagerViaShizuku(pkg, activity)
            if (!started) {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { startActivity(intent) }
                }
            }
        }
    }

    private fun downloadLatestKsu() {
        val profile = engine.profileByName(selectedProfileName) ?: run {
            Toast.makeText(this, R.string.no_profile_selected, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val result = engine.fetchLatestKsuFor(profile)
            engine.appendLog("[KernelSU] $result")
            latestKsuTag = engine.latestKsuTag()
            Toast.makeText(this@MainActivity, result, Toast.LENGTH_LONG).show()
        }
    }

    private fun resetProfiles() {
        engine.resetProfiles()
        refreshProfiles()
        selectedProfileName = engine.detectDevice().matchedProfileName?.takeIf { engine.profileByName(it) != null }
        Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show()
    }

    private fun copyLogs() {
        val text = engine.logLines.value.joinToString("\n")
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("uniroot-log", text))
        Toast.makeText(this, R.string.action_copy, Toast.LENGTH_SHORT).show()
    }

    private fun shareRunLog(file: File) {
        runCatching {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, file.name))
        }
    }

    /** NEW METHOD (fast): DirtyFrag engine, no profile, no privilege needed. */
    private fun launchNewMethod() {
        // The GhostSam engine (the proven working one on this device)
        engine.clearLogs()
        sheetVisible = true
        sheetDismissible = false
        newMethodProgress = 5
        newMethodStage = "Loading DF engine…"
        engine.appendLog("[NewMethod] moteur DF (base 4.4.8 éprouvée, DirtyFrag CVE-2026-43284) — " + (if (ksuNextMode) "KernelSU Next" else "KernelSU"))
        lifecycleScope.launch {
            engine.setRunning(true)
            val t0 = android.os.SystemClock.elapsedRealtime()
            var rc = -1
            try {
                rc = withContext(Dispatchers.IO) {
                    com.uniroot.app.newmethod.DfEngine.run(
                        this@MainActivity, ksuNextMode, newMethodSoftReboot,
                        { line -> engine.appendLog(line.trimEnd()) },
                        { pct, stage ->
                            newMethodProgress = pct
                            if (stage.isNotEmpty()) newMethodStage = stage
                        })
                }
                engine.appendLog("[NewMethod] DF rc=$rc")
                if (rc == com.uniroot.app.newmethod.DfEngine.DIAGNOSTIC_ONLY_COMPLETE) {
                    newMethodProgress = 100
                    newMethodStage = "Diagnostic complete — root chain skipped"
                    Toast.makeText(this@MainActivity,
                        "Page-cache diagnostic complete; root chain was not run",
                        Toast.LENGTH_LONG).show()
                } else if (rc == 0) {
                    newMethodProgress = 100
                    newMethodStage = "Phone rooted ✓"
                    engine.markRooted("DF")
                    Toast.makeText(this@MainActivity, R.string.phone_rooted_toast, Toast.LENGTH_LONG).show()
                    if (newMethodSoftReboot) {
                        engine.appendLog("[NewMethod] auto soft reboot…")
                        delay(2_500)
                        runCatching {
                            Runtime.getRuntime().exec(arrayOf("su", "-c", "setprop ctl.restart zygote"))
                        }
                    } else {
                        delay(4_000)
                        restartKsuManager()
                    }
                } else {
                    newMethodStage = "Failed (rc=$rc)"
                    Toast.makeText(this@MainActivity, R.string.new_method_failed, Toast.LENGTH_LONG).show()
                }
            } finally {
                engine.setRunning(false)
                sheetDismissible = true
                runCatching {
                    engine.endRunLog(
                        if (rc == 0) "Success" else if (rc == com.uniroot.app.newmethod.DfEngine.DIAGNOSTIC_ONLY_COMPLETE) "Diagnostic" else "Failed",
                        "GhostSam",
                        android.os.SystemClock.elapsedRealtime() - t0)
                }
                runLogs = engine.listRunLogs()
            }
        }
    }

    private fun launchRun() {
        val profile = engine.profileByName(selectedProfileName) ?: return
        // Shizuku profiles: S26 Ultra / Z Fold 8 (by name) or a profile with the
        // per-profile "Use Shizuku" switch on. The user sees exactly what blocks.
        val wantShizuku = RootEngine.profileNeedsShizuku(profile)

        if (wantShizuku) {
            if (!engine.shizukuBinderActive()) {
                engine.clearLogs()
                sheetVisible = true
                sheetDismissible = true
                engine.appendLog("[!] Shizuku binder is NOT active.")
                engine.appendLog("[!] Start Shizuku (wireless debugging) first, then run again.")
                return
            }
            if (!engine.shizukuPermissionGranted()) {
                engine.appendLog("[!] Shizuku permission missing — accepting the prompt, run again after.")
                engine.requestShizukuPermission()
                sheetVisible = true
                sheetDismissible = true
                return
            }
            shizukuEnabled = true
        }

        engine.setLastRunProfile(profile.name)
        engine.clearLogs()
        sheetVisible = true
        sheetDismissible = false
        if (autoMatched) engine.appendLog("[Mode] Profile auto-detected for this device")
        engine.appendLog("[Mode] " + (if (shizukuEnabled || wantShizuku) "Shizuku (shell UID 2000)" else "Local (app context)"))

        lifecycleScope.launch {
            engine.setRunning(true)
            var status = "Crash"
            try {
                status = engine.runExecutionPipeline(profile, shizukuEnabled || wantShizuku)
                engine.appendLog("[Pipeline] Finished: $status")
                runLogs = engine.listRunLogs()
                if (status == "Success") {
                    engine.refreshRootedLive()
                    // Log sheet closes itself. Instant root has PRIORITY: the
                    // offer shows right away and the manager restart only
                    // happens once the popup is resolved (or if there is none) —
                    // the 12 s delay stays, it protects against the post
                    // late-load freezes observed during the manager relaunch.
                    delay(1200)
                    sheetVisible = false
                    refreshInstantRootState()
                    // Offer on EVERY successful manual root while instant
                    // root is not set up yet — the manager restart waits for
                    // the popup to be resolved (Enable / Not now).
                    val offer = !engine.instantRootEnabled &&
                        !instantRootInstalled && InstantRoot(this@MainActivity).assetsAvailable()
                    if (offer) {
                        instantRootOfferVisible = true
                    } else {
                        delay(12_000)
                        restartKsuManager()
                    }
                }
            } catch (e: Exception) {
                // Nothing may fail silently: any exception lands in the visible log.
                engine.appendLog("[Error] Unexpected: ${e.javaClass.simpleName}: ${e.message}")
                android.util.Log.e("UniRoot", "pipeline failed", e)
                runCatching { engine.abandonActiveRunLog() }
                runLogs = engine.listRunLogs()
            } finally {
                engine.setRunning(false)
                sheetDismissible = true
            }
        }
    }

    // ------------------------------------------------------------------
    // Instant root (DFReroot second stage)
    // ------------------------------------------------------------------

    private fun refreshInstantRootState() {
        instantRootEnabled = engine.instantRootEnabled
        instantRootInstalled = InstantRoot(this).isHelperInstalled()
        // Instant root and the new method SHARE the V3 boot engine: the
        // receiver component follows BOTH switches (auto-root is separate
        // and untouched).
        syncBootReceiverEnabled()
    }

    /**
     * THE shared start-at-boot component: enabled when the new method's
     * "Start on boot" OR instant root is on. Disabled only when both are off.
     */
    private fun syncBootReceiverEnabled() {
        val enabled = newMethodBootEnabled || instantRootEnabled
        val cn = android.content.ComponentName(
            this, com.uniroot.app.newmethod.NewMethodBootReceiver::class.java)
        runCatching {
            packageManager.setComponentEnabledSetting(
                cn,
                if (enabled) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP,
            )
        }
    }

    /**
     * Called on resume: right after the setup soft reboot the helper still has
     * to be installed — ask the user (Android shows its own install dialog on
     * Install). If the user just came back from installing, complete the setup.
     */
    private fun checkInstantRootLanding() {
        val stage = engine.instantRootSetupStage
        if (stage != RootEngine.INSTANT_STAGE_INJECTED && stage != RootEngine.INSTANT_STAGE_QUIET) return
        val installed = InstantRoot(this).isHelperInstalled()
        if (installed) {
            engine.instantRootEnabled = true
            engine.instantRootSetupStage = ""
            engine.instantRootPrompted = true
            refreshInstantRootState()
            wakeHelperOnce()
            Toast.makeText(this, R.string.instant_root_install_ready, Toast.LENGTH_LONG).show()
            engine.appendLog("[InstantRoot] " + getString(R.string.instant_root_root_current_boot))
        } else if (!instantRootInstallVisible) {
            // Proven flow: UniRoot popup -> user taps -> native installer.
            instantRootInstallVisible = true
        }
    }

    /**
     * Helpers installed BEFORE the wake fix are stuck in Android's stopped
     * state (no BOOT_COMPLETED ever) — wake them once on first 4.3.9+ launch.
     */
    private fun maybeWakeHelper() {
        if (!engine.instantRootEnabled || !instantRootInstalled) return
        val prefs = getSharedPreferences("uniroot_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("helper_woken", false)) return
        wakeHelperOnce()
    }

    /**
     * THE stopped-state trap: a freshly installed app does not receive
     * BOOT_COMPLETED until it is launched once. The helper has no launcher
     * icon, so UniRoot launches its activity explicitly once (and refocuses
     * UniRoot immediately) — after that the boot receiver is alive forever.
     */
    private fun wakeHelperOnce() {
        runCatching {
            // Reliable path first: clear the stopped state via root. The
            // activity launch below stays as the no-root fallback.
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { InstantRoot(this@MainActivity).unstopHelper() }
                }
            }
            val wake = Intent().setClassName(
                InstantRoot.PKG_REROOT,
                InstantRoot.PKG_REROOT + ".MainActivity",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(wake)
            // Mark woken ONLY on success — a failed wake must be retried.
            getSharedPreferences("uniroot_prefs", MODE_PRIVATE)
                .edit().putBoolean("helper_woken", true).apply()
            lifecycleScope.launch {
                delay(1_500)
                runCatching {
                    startActivity(
                        Intent(this@MainActivity, MainActivity::class.java)
                            .addFlags(
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                            )
                    )
                }
            }
        }
    }

    /**
     * Instant-root setup — ONE time, popup-driven:
     * Enable → (root channel: exploit socket, or app su with one manager open)
     * → key injected into packages.xml → framework restart → UniRoot asks the
     * user to INSTALL the helper via Android's own install dialog. From then
     * on the helper is controlled by UniRoot and re-roots every boot.
     */
    private fun enableInstantRoot() {
        val instant = InstantRoot(this) { line -> engine.appendLog(line) }
        // Notifications are the channel that survives the soft reboot — ask
        // for the grant up front (Android 13+ silently drops them otherwise).
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 102) }
        }
        // Overlay permission = toasts from the boot service are actually shown
        // (this is how the old auto-root toasts worked).
        if (!android.provider.Settings.canDrawOverlays(this)) {
            runCatching {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
        }
        lifecycleScope.launch {
            instant.record(getString(R.string.instant_root_setup_start))
            instant.record(getString(R.string.instant_root_setup_su_wait))
            // Shizuku-like flow: quick probe first; if superuser is not granted
            // yet, show the popup + open the manager and KEEP probing — the
            // setup continues automatically the moment the grant lands.
            var suOut = withContext(Dispatchers.IO) { instant.suId(timeoutSec = 8) }
            if (suOut == null) {
                // Popup explains what to do; the manager opens ONLY when the
                // user presses "Open manager". Probing keeps running in the
                // background and the setup resumes itself on grant.
                superuserNeededVisible = true
                for (i in 1..48) {
                    delay(5_000)
                    suOut = withContext(Dispatchers.IO) { instant.suId(timeoutSec = 8) }
                    if (suOut != null) break
                }
                superuserNeededVisible = false
            }
            if (suOut == null) {
                instant.record("[InstantRoot] Superuser was not granted in time — allow UniRoot in KernelSU (Next) > Superuser, then press Enable again.")
                instant.record(getString(R.string.instant_root_setup_failed))
                Toast.makeText(this@MainActivity, R.string.instant_root_need_root, Toast.LENGTH_LONG).show()
                return@launch
            }
            instant.record("[InstantRoot] Superuser OK")
            Toast.makeText(this@MainActivity, R.string.instant_root_su_ok, Toast.LENGTH_SHORT).show()
            // The user may have gone to the manager — come back to UniRoot.
            runCatching {
                startActivity(
                    Intent(this@MainActivity, MainActivity::class.java)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                )
            }
            delay(1_000)
            // Helper already in place (UniRoot reinstalled, helper untouched):
            // the key is already trusted — just switch instant root back on.
            if (instant.isHelperInstalled()) {
                // THE disabled-helper trap: "Disable root" leaves the helper
                // pm-disabled, and a disabled package NEVER receives
                // BOOT_COMPLETED — always re-enable before declaring ON.
                withContext(Dispatchers.IO) { instant.setHelperEnabled(true) }
                // (Re)stage the SELECTED profile's own ksud: flipping profiles
                // + re-enabling switches the flavor that comes back at boot.
                val usedProfile = engine.profileByName(selectedProfileName)
                if (!usedProfile?.pathKsud.isNullOrBlank()) {
                    withContext(Dispatchers.IO) {
                        instant.stageProfileKsud(usedProfile!!.pathKsud, usedProfile.flavor)
                    }
                }
                engine.instantRootEnabled = true
                engine.instantRootPrompted = true
                refreshInstantRootState()
                wakeHelperOnce()
                instant.record("[InstantRoot] Helper already installed — instant root is ON (no reboot needed).")
                Toast.makeText(this@MainActivity, R.string.instant_root_switch_on_toast, Toast.LENGTH_LONG).show()
                return@launch
            }
            // Nothing destructive happens without an explicit Continue.
            pendingInstant = instant
            injectConfirmVisible = true
        }
    }

    /** Called from the explicit Continue: inject, restart the framework. */
    private fun continueInstantRootSetup() {
        val instant = pendingInstant ?: InstantRoot(this) { line -> engine.appendLog(line) }
        pendingInstant = null
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, R.string.instant_root_injecting, Toast.LENGTH_SHORT).show()
            val ok = withContext(Dispatchers.IO) {
                instant.stageApks() && instant.inject()
            }
            if (!ok) {
                instant.record(getString(R.string.instant_root_setup_failed))
                return@launch
            }
            // Stage the LAST USED PROFILE's own ksud as the boot payload: the
            // helper keeps /data/system/dfreroot-ksud verbatim, so the flavor
            // that comes back at every boot is exactly this profile's flavor
            // (KernelSU or Next), custom profiles included.
            val usedProfile = engine.profileByName(selectedProfileName)
            val ksudSrc = usedProfile?.pathKsud
            if (!ksudSrc.isNullOrBlank()) {
                val staged = withContext(Dispatchers.IO) {
                    instant.stageProfileKsud(ksudSrc, usedProfile?.flavor ?: "kernelsu")
                }
                instant.record(
                    if (staged) "[InstantRoot] profile ksud staged to /data/system/dfreroot-ksud ($ksudSrc)"
                    else "[InstantRoot] profile ksud staging failed — helper will use its bundled fallback",
                )
            }
            engine.instantRootSetupStage = RootEngine.INSTANT_STAGE_INJECTED
            // Enabled from THIS moment — it must survive the reboot even if
            // the app is never opened again (the boot receiver keys on it).
            engine.instantRootEnabled = true
            engine.instantRootPrompted = true
            // Exclusive by design: the boot re-root replaces the exploit auto-root.
            if (engine.autoRootOnBoot) {
                engine.autoRootOnBoot = false
                autoRootOnBoot = false
            }
            refreshInstantRootState()
            // Persistent notification FIRST (it survives the soft reboot in
            // the shade and is re-posted at boot by AutoRootReceiver); as soon
            // as it is posted we fire the soft reboot.
            runCatching {
                val nm = getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel("auto_root") == null) {
                    nm.createNotificationChannel(
                        NotificationChannel("auto_root", "Auto-root", NotificationManager.IMPORTANCE_DEFAULT))
                }
                val open = packageManager.getLaunchIntentForPackage(packageName)
                val n = Notification.Builder(this@MainActivity, "auto_root")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("UniRoot — Instant Root")
                    .setContentText(getString(R.string.instant_root_soft_reboot_notif))
                    .setAutoCancel(true)
                    .setOngoing(true)
                    .apply {
                        open?.let {
                            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            setContentIntent(
                                PendingIntent.getActivity(
                                    this@MainActivity, 3, it,
                                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                                )
                            )
                        }
                    }
                    .build()
                nm.notify(45, n)
            }
            instant.record(getString(R.string.instant_root_setup_reboot))
            delay(1_000)
            Toast.makeText(this@MainActivity, R.string.instant_root_restarting, Toast.LENGTH_LONG).show()
            delay(4_000)
            // Shown twice — nobody can miss it before the framework restarts.
            Toast.makeText(this@MainActivity, R.string.instant_root_launch_after, Toast.LENGTH_LONG).show()
            delay(4_000)
            Toast.makeText(this@MainActivity, R.string.instant_root_launch_after, Toast.LENGTH_LONG).show()
            delay(4_000)
            withContext(Dispatchers.IO) { instant.softReboot() }
        }
    }

    /** Full removal: helper apps + packages.xml key (needs live root). */
    private fun uninstallHelper() {
        val instant = InstantRoot(this) { line -> engine.appendLog(line) }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { instant.uninstallAll() }
            engine.instantRootEnabled = false
            engine.instantRootSetupStage = ""
            refreshInstantRootState()
            Toast.makeText(
                this@MainActivity,
                if (ok) R.string.instant_root_uninstalled else R.string.instant_root_need_root,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** Turn the boot re-root off (keeps the helper installed but disabled). */
    private fun disableInstantRoot() {
        engine.instantRootEnabled = false
        // Also drop a pending setup stage: a disabled instant root must NOT
        // fire the install popup at the next boot.
        engine.instantRootSetupStage = ""
        refreshInstantRootState()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { InstantRoot(this@MainActivity).setHelperEnabled(false) }
            engine.appendLog(if (ok) "[InstantRoot] Helper disabled" else "[InstantRoot] Helper not disabled (no root right now)")
        }
        Toast.makeText(this, R.string.instant_root_disabled_toast, Toast.LENGTH_LONG).show()
    }

    /** Main button while rooted: make sure nothing re-roots at the next boot. */
    private fun disableRoot() {
        engine.instantRootEnabled = false
        engine.instantRootSetupStage = ""
        if (engine.autoRootOnBoot) {
            engine.autoRootOnBoot = false
            autoRootOnBoot = false
        }
        refreshInstantRootState()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { InstantRoot(this@MainActivity).setHelperEnabled(false) }
        }
        Toast.makeText(this, R.string.instant_root_disabled_toast, Toast.LENGTH_LONG).show()
    }

    /** Advanced-settings switch. Off→On with no helper installed starts the full
     * setup (works while the phone is rooted: exploit root or instant-root boot). */
    private fun applyInstantRootEnabled(enabled: Boolean) {
        if (enabled && !instantRootInstalled) {
            if (engine.autoRootOnBoot) {
                engine.autoRootOnBoot = false
                autoRootOnBoot = false
                Toast.makeText(this, R.string.autoroot_disabled_for_instant, Toast.LENGTH_LONG).show()
            }
            enableInstantRoot()
            return
        }
        engine.instantRootEnabled = enabled
        refreshInstantRootState()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { InstantRoot(this@MainActivity).setHelperEnabled(enabled) }
        }
        Toast.makeText(this, if (enabled) R.string.instant_root_switch_on_toast else R.string.instant_root_disabled_toast, Toast.LENGTH_LONG).show()
    }

    private fun setupSystemBars() {
        val controller = window.decorView.windowInsetsController ?: return
        val lightStatus = if (resources.getBoolean(R.bool.window_light_status_bar)) {
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        } else 0
        val lightNavigation = if (resources.getBoolean(R.bool.window_light_navigation_bar)) {
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        } else 0
        controller.setSystemBarsAppearance(
            lightStatus or lightNavigation,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
    }
}
