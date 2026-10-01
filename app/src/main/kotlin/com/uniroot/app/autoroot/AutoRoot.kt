package com.uniroot.app.autoroot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.uniroot.app.R
import com.uniroot.app.engine.InstantRoot
import com.uniroot.app.engine.RootEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Auto-root on boot: BOOT_COMPLETED -> foreground service -> re-run the last
 * used profile once per fresh boot (boot-count guarded). The single ongoing
 * notification is a promoted live activity (Android 16 ProgressStyle ->
 * Samsung Now Bar / Live notifications) tracking the root stages, with a
 * Stop action. Toasts guide the user: "root starts in 1 minute" at boot,
 * "don't touch the phone" when the exploit runs, and the final result.
 */
class AutoRootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Featherweight: only raw prefs here — heavy init happens in the service IO scope.
        val prefs = context.getSharedPreferences("uniroot_prefs", Context.MODE_PRIVATE)
        // Instant root: the DFReroot helper re-roots the phone on its own at
        // boot. UniRoot only steps in ONCE, right after the setup soft reboot,
        // to install the system-UID helper (su is still alive then).
        val stage = prefs.getString("instant_root_setup_stage", "") ?: ""
        if (stage == RootEngine.INSTANT_STAGE_INJECTED || stage == RootEngine.INSTANT_STAGE_QUIET) {
            runCatching { context.startForegroundService(Intent(context, AutoRootService::class.java)) }
            return
        }
        // Instant root boot re-root now runs through THE shared V3 engine
        // (NewMethodBootReceiver, enabled from the instant root toggle — see
        // MainActivity.syncBootReceiverEnabled). Nothing to do here; auto-root
        // below stays exactly as it was.
        if (prefs.getBoolean("instant_root_enabled", false)) return
        if (!prefs.getBoolean("auto_root_on_boot", false)) return
        if (prefs.getString("last_run_profile", null) == null) return
        if (prefs.getString("profiles_json", null) == null) return
        val bootCount = runCatching {
            AndroidSettings.Global.getInt(context.contentResolver, AndroidSettings.Global.BOOT_COUNT, 0)
        }.getOrDefault(0)
        if (prefs.getInt("auto_root_boot_count", -1) == bootCount) return

        val service = Intent(context, AutoRootService::class.java)
        runCatching { context.startForegroundService(service) }
    }
}

class AutoRootService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var engine: RootEngine
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Toast from any thread (posted to the main looper). */
    private fun toast(text: String, long: Boolean = true) {
        val duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        mainHandler.post { runCatching { Toast.makeText(applicationContext, text, duration).show() } }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            hideOverlay()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        stopRequested = false
        // Settle phase: the user CAN touch the phone — one guiding toast only.
        // Instant-root paths get their own wording (the 1-minute exploit
        // warning only makes sense for the auto-root exploit rerun).
        val prefsEarly = getSharedPreferences("uniroot_prefs", Context.MODE_PRIVATE)
        val earlyStage = prefsEarly.getString("instant_root_setup_stage", "") ?: ""
        val earlyInstant = prefsEarly.getBoolean("instant_root_enabled", false) ||
            earlyStage == RootEngine.INSTANT_STAGE_INJECTED || earlyStage == RootEngine.INSTANT_STAGE_QUIET
        if (!earlyInstant) {
            toast(getString(R.string.autoroot_start_toast), long = true)
        }
        ensureChannel()
        // Brand the mandatory foreground notification per mode: the user must
        // never see "Auto-root" wording while instant root is the active one.
        val preparingText =
            if (earlyInstant) getString(R.string.instant_root_preparing)
            else getString(R.string.autoroot_preparing)
        val startNotif = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("UniRoot")
            .setContentText(preparingText)
            .setOngoing(true)
            .build()
        runCatching { startForeground(NOTIF_ID, startNotif) }
        scope.launch {
            // ---- Instant root finish-setup: NO auto installer at boot. The
            // user opens UniRoot, the landing popup offers the install
            // (checkInstantRootLanding -> native installer). Only a pointer
            // notification is posted so the step is not forgotten.
            val prefs = getSharedPreferences("uniroot_prefs", Context.MODE_PRIVATE)
            val stage = prefs.getString("instant_root_setup_stage", "") ?: ""
            if (stage == RootEngine.INSTANT_STAGE_INJECTED || stage == RootEngine.INSTANT_STAGE_QUIET) {
                postInstantRootTapNotification()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            // Instant root: DFReroot owns the boot re-root. UniRoot only
            // watches and confirms — it must NOT run the exploit on top.
            // Branch BEFORE the heavy engine init: the "will start" feedback
            // (toast + notification) must land immediately, not after init.
            if (prefs.getBoolean("instant_root_enabled", false)) {
                // Root is not up yet — grant attempt fails fast, retried the
                // moment su works inside the watch loop.
                runCatching { InstantRoot(applicationContext).ensureNotificationPermission() }
                toast(getString(R.string.instant_root_will_start))
                watchInstantRoot()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            engine = RootEngine(applicationContext)
            // Heavy init (profiles, asset refresh, MD5s) on IO — never the main thread.
            runCatching { engine.initialize() }
            val profileName = engine.lastRunProfile()
            val profile = engine.profileByName(profileName)
            if (profile == null) { stopSelf(); return@launch }

            // Once-per-boot guard (recorded BEFORE running so a crash also counts).
            val bootCount = runCatching {
                AndroidSettings.Global.getInt(contentResolver, AndroidSettings.Global.BOOT_COUNT, 0)
            }.getOrDefault(0)
            if (engine.autoRootBootCount() == bootCount) { stopSelf(); return@launch }
            engine.setAutoRootBootCount(bootCount)

            val needsShizuku = RootEngine.profileNeedsShizuku(profile)
            var status = "Crash"
            try {
                updateLive(8, getString(R.string.autoroot_settling))
                // Let the system finish booting (selinux loads, vendors up, slab
                // state sane). The payload itself waits for an allocator quiet
                // window — starting later lets it find one immediately.
                if (!waitWithCancel(90_000L)) { stopSelf(); return@launch }

                if (needsShizuku) {
                    updateLive(12, getString(R.string.autoroot_waiting_shizuku))
                    if (!waitForShizuku(timeoutMs = 5 * 60_000L)) {
                        postResult(getString(R.string.autoroot_shizuku_missing), success = false)
                        return@launch
                    }
                    if (!engine.shizukuPermissionGranted()) {
                        postResult(getString(R.string.autoroot_permission_missing), success = false)
                        return@launch
                    }
                }

                // Flavor guard: only block when a DIFFERENT flavor's module is
                // already loaded (switching then would contaminate the boot).
                // A phone with NO root at boot always runs normally.
                if (engine.ksuModuleLoaded()) {
                    val loaded = engine.loadedKsuFlavor()
                    if (loaded != null && loaded != profile.flavor) {
                        hideOverlay()
                        postResult(getString(R.string.autoroot_flavor_changed), success = false)
                        return@launch
                    }
                }

                // Exploit is launching: from here on the phone must NOT be touched.
                showOverlay(getString(R.string.autoroot_overlay_running))
                toast(getString(R.string.autoroot_dont_touch_until_done))
                launch {
                    delay(8_000L)
                    toast(getString(R.string.autoroot_take_2min))
                }
                updateLive(20, getString(R.string.autoroot_running, profile.name))
                engine.clearLogs()
                engine.progressListener = { p, label -> updateLive(p, "Auto-root: $label") }
                var runAttempt = 0
                while (runAttempt < 3 && !stopRequested) {
                    if (runAttempt > 0) {
                        updateLive(10, getString(R.string.autoroot_retrying, runAttempt))
                        if (!waitWithCancel(10_000L)) break
                    }
                    engine.clearLogs()
                    status = engine.runExecutionPipeline(profile, needsShizuku)
                    if (status == "Success" || status == "Reboot required") break
                    runAttempt++
                }
                engine.progressListener = null

                if (status == "Success") {
                    engine.refreshRootedLive()
                    toast(getString(R.string.autoroot_run_success))
                    postResult(getString(R.string.autoroot_run_success), success = true)
                } else {
                    toast(getString(R.string.autoroot_run_failed))
                    postResult(getString(R.string.autoroot_run_failed), success = false)
                }
            } catch (e: Exception) {
                postResult("Auto-root error: ${e.message}", success = false)
            } finally {
                hideOverlay()
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Instant-root boot: DFReroot re-roots ~1-2 min after boot. Watch for the
     * su channel to come back and confirm with a mini bottom popup.
     */
    /** Waits for DFReroot to restore root. True = root is back. */
    /** Watches for DFReroot to restore root and confirms with a mini popup. */
    private suspend fun watchInstantRoot() {
        // ONE notification — the live one (NOTIF_ID) — carries the whole
        // flow: will start -> scanning (2 s scan, exact timing) -> final
        // result posted by the caller after stopForeground. Toasts are a
        // best-effort bonus only.
        updateLive(15, getString(R.string.instant_root_will_start))
        if (!waitWithCancel(15_000L)) return
        val instant = InstantRoot(applicationContext)
        var elapsed = 0
        for (i in 1..450) {
            if (stopRequested) return
            val rooted = instant.rootSignals() || instant.suId(timeoutSec = 3) != null
            if (rooted) {
                // su just came up: grant notifications NOW if the user had
                // not, so the final notification is actually visible.
                val ns = instant.ensureNotificationPermission()
                android.util.Log.i("AutoRoot", "notification grant: $ns")
                toast(getString(R.string.phone_rooted_toast))
                postResult(getString(R.string.instant_root_rooted_notif), success = true)
                // Managers cache their "working" state: force-stop both so the
                // next open shows the fresh, live root.
                runCatching { instant.su("am force-stop me.weishu.kernelsu; am force-stop com.rifsxd.ksunext", timeoutSec = 20) }
                return
            }
            elapsed += 2
            if (elapsed % 10 == 0) {
                updateLive(
                    (20 + elapsed / 4).coerceAtMost(90),
                    getString(R.string.instant_root_scanning, elapsed),
                )
            }
            delay(2_000L)
        }
        // ~20 min without root: report it with instant-root wording.
        postResult(getString(R.string.instant_root_watch_failed), success = false)
    }

    /**
     * Setup not finished after the soft reboot: the helper installs via
     * Android's own dialog inside UniRoot — post a tap-through notification.
     */
    private fun postInstantRootTapNotification() {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            ensureChannel()
            val builder = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.instant_root_finish_title))
                .setContentText(getString(R.string.instant_root_fallback))
                .setAutoCancel(true)
            packageManager.getLaunchIntentForPackage(packageName)?.let { open ->
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        this, 2, open,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
            }
            manager.notify(RESULT_NOTIF_ID, builder.build())
        }
    }

    /**
     * Boot-scoped notification (id 43): toasts from a boot-started service can
     * be suppressed/queued on recent Android; a notification is always shown
     * and stays in the shade until tapped.
     */
    private fun notifyBoot(text: String) = runCatching {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Auto-root", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("UniRoot — Instant Root")
            .setContentText(text)
            .setAutoCancel(true)
            .apply {
                open?.let {
                    setContentIntent(
                        PendingIntent.getActivity(
                            this@AutoRootService, 4, it,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                    )
                }
            }
            .build()
        manager.notify(BOOT_NOTIF_ID, n)
    }.getOrDefault(Unit)

    /** Waits [totalMs] in 1 s steps; returns false as soon as the user pressed Stop. */
    private suspend fun waitWithCancel(totalMs: Long): Boolean {
        var waited = 0L
        while (waited < totalMs) {
            if (stopRequested) return false
            delay(1_000L)
            waited += 1_000L
        }
        return !stopRequested
    }

    private suspend fun waitForShizuku(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (stopRequested) return false
            if (Shizuku.pingBinder()) {
                if (engine.shizukuPermissionGranted()) return true
            }
            delay(5_000L)
        }
        return false
    }

    private var overlayView: View? = null
    private var lastBuilt: Notification? = null

    /** Always-on-top banner shown only while the exploit is actually running. */
    private fun showOverlay(text: String) {
        if (!AndroidSettings.canDrawOverlays(this)) return
        val wm = getSystemService(WindowManager::class.java)
        overlayView?.let { runCatching { wm.removeView(it) } }
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(-0x1)
            setBackgroundColor(0xCC101820.toInt())
            gravity = Gravity.CENTER
            setPadding(32, 28, 32, 28)
            textSize = 16f
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP
        runCatching { wm.addView(tv, params); overlayView = tv }
    }

    private fun hideOverlay() {
        overlayView?.let { v -> runCatching { getSystemService(WindowManager::class.java).removeView(v) } }
        overlayView = null
    }

    /**
     * THE single notification: foreground service + promoted live activity
     * (Android 16 ProgressStyle -> Samsung Now Bar) + Stop action.
     */
    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel(CHANNEL)
        when {
            existing == null -> manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Auto-root", NotificationManager.IMPORTANCE_DEFAULT))
            // Channels are immutable: an old LOW-importance channel must be
            // deleted and recreated or the boot notifications stay silent.
            existing.importance < NotificationManager.IMPORTANCE_DEFAULT -> {
                manager.deleteNotificationChannel(CHANNEL)
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Auto-root", NotificationManager.IMPORTANCE_DEFAULT))
            }
        }
    }

    private fun postLive(progress: Int, text: String, withStop: Boolean) = runCatching {
        ensureChannel()
        val manager = getSystemService(NotificationManager::class.java)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("UniRoot")
            .setContentText(text)
            .setOngoing(true)
        if (android.os.Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(
                Notification.ProgressStyle()
                    .setProgress(progress.coerceIn(0, 100))
            )
            // Ask the system to promote this notification (Now Bar / lock screen).
            runCatching { builder.setRequestPromotedOngoing(true) }
            builder.extras.putBoolean("android.requestPromotedOngoing", true)
            runCatching { builder.setShortCriticalText(text) }
        } else {
            builder.setProgress(100, progress.coerceIn(0, 100), progress in 1..99)
        }
        if (withStop) {
            val stopIntent = PendingIntent.getService(
                this, 0,
                Intent(this, AutoRootService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(R.string.autoroot_stop),
                    stopIntent,
                ).build(),
            )
        }
        val notif = builder.build()
        lastBuilt = notif
        manager.notify(NOTIF_ID, notif)
    }.getOrDefault(Unit)

    private fun updateLive(progress: Int, text: String) {
        postLive(progress, text, withStop = true)
    }

    /** Updates the live notification with the final result and detaches it. */
    private fun postResult(text: String, success: Boolean) = runCatching {
        val manager = getSystemService(NotificationManager::class.java)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (success) "UniRoot — rooted" else "UniRoot — auto-root failed")
            .setContentText(text)
            .setAutoCancel(true)
        if (success) {
            // Open the manager of the flavor that was actually rooted
            // (recorded at root time) — never the other one.
            val next = getSharedPreferences("uniroot_prefs", Context.MODE_PRIVATE)
                .getString("loaded_flavor", "kernelsu") == "kernelsu_next"
            val pkg = if (next) "com.rifsxd.ksunext" else "me.weishu.kernelsu"
            packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
                val pi = PendingIntent.getActivity(
                    this, 1, launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                builder.addAction(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_manage),
                        getString(R.string.open_manager, if (next) "KernelSU Next" else "KernelSU"),
                        pi,
                    ).build(),
                )
            }
        }
        manager.notify(RESULT_NOTIF_ID, builder.build())
    }.getOrDefault(Unit)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "auto_root"
        private const val NOTIF_ID = 41
        private const val RESULT_NOTIF_ID = 42
        private const val BOOT_NOTIF_ID = 43
        const val ACTION_STOP = "com.uniroot.app.autoroot.STOP"
        @Volatile var stopRequested = false
    }
}

/**
 * Receives PackageInstaller session status for the helper install fired at
 * boot (soft-reboot finish-setup): starts the system confirm dialog when a
 * user action is pending, celebrates "Instant Root is set up" on success.
 */
class InstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            android.content.pm.PackageInstaller.EXTRA_STATUS,
            android.content.pm.PackageInstaller.STATUS_FAILURE,
        )
        when (status) {
            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { if (confirm != null) context.startActivity(confirm) }
            }
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> {
                context.getSharedPreferences("uniroot_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("instant_root_setup_stage", "")
                    .putBoolean("instant_root_enabled", true)
                    .apply()
                Toast.makeText(
                    context,
                    context.getString(R.string.instant_root_install_ready),
                    Toast.LENGTH_LONG,
                ).show()
            }
            else -> {
                val msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "failure $status"
                Toast.makeText(
                    context,
                    context.getString(R.string.instant_root_setup_failed) + " ($msg)",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
