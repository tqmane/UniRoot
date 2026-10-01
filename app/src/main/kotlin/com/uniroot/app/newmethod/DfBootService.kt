package com.uniroot.app.newmethod

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Foreground service that runs the NEW method engine at boot — the SAME
 * process-priority pattern as the OLD method's AutoRootService (which does
 * survive boot on this phone). A bare receiver thread gets its process
 * trimmed during the boot memory pressure; a foreground service does not.
 *
 * The ENGINE IS UNTOUCHED (DfEngine / DFBridge / the proven 4.4.53 libs).
 * This service only orchestrates the boot run:
 *  1. wait for the credential storage (FBE unlocks at first unlock), ≤4 min
 *  2. settle, then run DfEngine; up to 6 attempts 30 s apart while /dev/df
 *     is absent (the boot storm races the first shots)
 *  3. a partial WakeLock keeps the CPU awake (the dirtyfrag6 trick)
 *  4. every line lands in new-method-boot.log AND logcat (tag UniRootNM)
 */
class DfBootService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        Thread({ runCatching { runBoot() } }, "uniroot-dfboot").start()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "New method boot",
                        NotificationManager.IMPORTANCE_MIN))
            }
            val notif = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("UniRoot")
                .setContentText("Auto-start at boot…")
                .setOngoing(true)
                .build()
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun bootLog(line: String) {
        Log.i(TAG, line)
        runCatching {
            val dir = getExternalFilesDir(null) ?: return
            java.io.File(dir, "new-method-boot.log").appendText(line + "\n")
        }
    }

    /** Credential storage probe: FBE keeps it locked until first unlock. */
    private fun ceReadable(): Boolean = runCatching {
        val probe = java.io.File(filesDir, ".boot-probe")
        probe.writeText("x")
        probe.delete()
        true
    }.getOrDefault(false)

    private fun runBoot() {
        val wl = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UniRoot:dfboot")
        runCatching { wl.acquire(600_000L) }
        try {
            val prefs = createDeviceProtectedStorageContext()
                .getSharedPreferences(NewMethodBootReceiver.PREFS, Context.MODE_PRIVATE)
            val next = prefs.getBoolean(NewMethodBootReceiver.PREF_NEXT, false)
            val softReboot = prefs.getBoolean(NewMethodBootReceiver.PREF_AUTO_SOFT_REBOOT, true)

            // 1. Wait for the first unlock (CE storage) — ≤4 min.
            var waited = 0
            while (!ceReadable() && waited < 240_000) {
                Thread.sleep(2_000)
                waited += 2_000
            }
            if (!ceReadable()) {
                bootLog("CE storage never unlocked — giving up this boot")
                return
            }

            // 2. Settle, then up to 6 attempts 30 s apart.
            Thread.sleep(8_000)
            for (attempt in 1..6) {
                if (java.io.File("/dev/df").exists()) {
                    bootLog("already hooked — root is up")
                    return
                }
                bootLog("=== boot attempt $attempt/6 (next=$next) ===")
                val rc = DfEngine.run(applicationContext, next, softReboot, { msg ->
                    bootLog(msg.trim())
                }, { _, _ -> })
                bootLog("=== attempt $attempt rc=$rc ===")
                if (rc == 0) return
                if (rc == DfEngine.KSUD_PREPARATION_FAILED) {
                    bootLog("target ksud preparation failed; not retrying this boot")
                    return
                }
                if (attempt < 6) {
                    Thread.sleep(30_000)
                    if (java.io.File("/dev/df").exists()) {
                        bootLog("hooked between attempts — done")
                        return
                    }
                }
            }
            bootLog("giving up after 6 attempts (next reboot retries)")
        } catch (t: Throwable) {
            Log.e(TAG, "boot run failed", t)
            bootLog("exception: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { wl.release() }
            runCatching {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    companion object {
        private const val TAG = "UniRootNM"
        private const val CHANNEL = "dfboot"
        private const val NOTIF_ID = 1002
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, DfBootService::class.java))
            }
        }
    }
}
