package com.uniroot.app.newmethod

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Le moteur new méthode ÉPROUVÉ (base 4.4.7/4.4.8, validé on-device avec le
 * ksud Next 3.4.0) : port DFRoot — libexp / libexpnext, APIs publiques IpSec,
 * ksud bind-mounté par le natif. Même signature que GhostSamRunner.run pour
 * un échange de moteur sans toucher aux appelants (MainActivity, boot
 * receiver).
 *
 * Le ksud stagé suit la sélection de l'accueil : custom > profil
 * (KsudNextProfiles : 3.3.0 / 3.4.0…) > fallback df/ksud-new-next — voir
 * DFBridge.stageKsud.
 *
 * Progression : la barre avance EN CONTINU pendant le natif (ticker +1 palier
 * toutes les 700 ms jusqu'à 90) et saute en avant à chaque jalon réel rapporté
 * par le moteur (SA IpSec, SPI, ksud stagé) — l'étape courante s'affiche,
 * plus de barre figée qui saute à 100.
 */
object DfEngine {

    const val KSUD_PREPARATION_FAILED = -2

    @Volatile private var stage: String = "Chargement moteur DF…"
    @Volatile private var milestone: Int = 12

    fun run(
        context: Context,
        ksuNext: Boolean,
        softReboot: Boolean,
        onLog: (String) -> Unit,
        onProgress: (Int, String) -> Unit,
    ): Int {
        val done = AtomicBoolean(false)
        stage = "Loading DF engine…"
        milestone = 12
        onProgress(milestone, stage)

        if (!DfKsudUpdater.ensureTargetKsud(context, next = ksuNext, onLog = onLog)) {
            stage = "Target KernelSU module unavailable"
            onProgress(100, stage)
            return KSUD_PREPARATION_FAILED
        }

        df.root.DFBridge.load(ksuNext)
        val flavor = if (ksuNext) "KernelSU Next" else "KernelSU"
        stage = "DF engine ready ($flavor)"
        milestone = 25
        onProgress(milestone, stage)

        // Creeping bar while the native works — the wait is VISIBLE.
        val ticker = Thread({
            while (!done.get()) {
                onProgress(milestone, stage)
                if (milestone < 90) milestone++
                runCatching { Thread.sleep(700) }
            }
        }, "df-progress").apply { isDaemon = true; start() }

        val rc = try {
            df.root.DFBridge.run(context, ksuNext, softReboot, object : df.root.IReporter {
                override fun report(msg: String?) {
                    if (msg.isNullOrBlank()) return
                    onLog(msg)
                    val line = msg.trim()
                    when {
                        line.startsWith("encap port") -> {
                            stage = "IpSec SA allocated…"; if (milestone < 45) milestone = 45
                        }
                        line.startsWith("spi:") -> {
                            stage = "SPI ready, transform in place…"; if (milestone < 52) milestone = 52
                        }
                        line.startsWith("staged") -> {
                            stage = "ksud staged, arming exploit…"; if (milestone < 62) milestone = 62
                        }
                        line.startsWith("exception") -> {
                            stage = "Engine error (see log)"; milestone = 92
                        }
                    }
                }
            })
        } finally {
            done.set(true)
            ticker.join(1500)
        }
        onProgress(100, if (rc == 0) "Phone rooted ✓" else "Failed (engine rc=$rc) - see log")
        return rc
    }
}
