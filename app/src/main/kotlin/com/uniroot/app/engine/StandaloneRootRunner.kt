package com.uniroot.app.engine

import android.content.Context
import com.uniroot.app.newmethod.DfKsudUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs the imported standalone CVE payloads while using UniRoot's own KSU stack. */
internal class StandaloneRootRunner(
    private val context: Context,
    private val appendLog: (String) -> Unit,
    private val runShizukuCommand: suspend (String) -> Int,
    private val captureShizukuOutput: suspend (String) -> String,
    private val startShizukuProcess: (String) -> Process,
    private val kernelSuVisible: suspend () -> Boolean,
    private val rootAlive: () -> Boolean,
) {
    private data class ProcessResult(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean,
    )

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun exploitSucceeded(log: String): Boolean =
        log.contains("done=1 root=1") && log.contains("exploit completed attempt=")

    suspend fun run(profile: DeviceProfile): String {
        require(profile.standalone) { "Not a standalone target profile" }
        require(profile.deviceType == "nothing" || profile.deviceType == "oneplus") {
            "Unsupported standalone target type: ${profile.deviceType}"
        }
        val payload = File(profile.pathSo)
        val targetHelperPath = profile.pathCveRoot
            ?: error("Standalone temporary-root helper path is missing")
        val targetHelper = File(targetHelperPath)
        val uniRootHelper = File(context.applicationInfo.nativeLibraryDir, "libcve43499root.so")
        require(payload.isFile && targetHelper.isFile && uniRootHelper.isFile) {
            "Standalone payload, temporary-root helper, or UniRoot helper is missing"
        }

        if (kernelSuVisible()) {
            appendLog("[+] KernelSU is already present; skipping another exploit run.")
            return "Success"
        }

        // The target KMI slot must contain a module from the same upstream
        // KernelSU flavor. Do not load the imported target ksud: it was built
        // with a different Manager package/signing identity.
        val ksud = ksudForCurrentTarget()
        val (payloadRemote, targetHelperRemote, logPath) = if (profile.deviceType == "nothing") {
            Triple(
                "/data/local/tmp/root-my-nothing-cve43499",
                "/data/local/tmp/cve-2026-43499-root",
                "/data/local/tmp/root-my-nothing-exploit.log",
            )
        } else {
            Triple(
                "/data/local/tmp/root-my-oneplus-pad3-cve43499",
                "/data/local/tmp/cve-2026-43499-root",
                "/data/local/tmp/root-my-oneplus-pad3-exploit.log",
            )
        }

        val identity = captureShizukuOutput("id; echo rc=\$?")
        require(identity.contains("uid=2000(shell)")) {
            "Shizuku is not running as shell: ${identity.trim()}"
        }

        appendLog("[Shizuku] Staging standalone payload and temporary-root helper…")
        val stage = listOf(
            "cp ${quote(payload.absolutePath)} ${quote(payloadRemote)}",
            "cp ${quote(targetHelper.absolutePath)} ${quote(targetHelperRemote)}",
            "chmod 755 ${quote(payloadRemote)} ${quote(targetHelperRemote)}",
        ).joinToString(" && ")
        val stageCode = runShizukuCommand(stage)
        require(stageCode == 0) { "Unable to stage standalone artifacts through Shizuku (rc=$stageCode)" }

        val launch = if (profile.deviceType == "nothing") {
            // Nothing's core61 profile uses its page-cache kicker while the payload runs.
            "/system/bin/mkdir -p /data/local/tmp/asteroids; " +
                "(i=0; while [ \$i -lt 400 ]; do i=\$((i + 1)); " +
                "/system/bin/head -c 200000 /dev/urandom > /data/local/tmp/asteroids/.kick\$i 2>/dev/null; " +
                "/system/bin/sync; /system/bin/rm -f /data/local/tmp/asteroids/.kick\$i; done) & " +
                "kicker=\$!; EXPLOIT_ATTEMPTS=24 EXPLOIT_ATTEMPT_TIMEOUT_SEC=300 " +
                "$payloadRemote > $logPath 2>&1; rc=\$?; " +
                "kill \$kicker 2>/dev/null; wait \$kicker 2>/dev/null; " +
                "/system/bin/rm -f /data/local/tmp/asteroids/.kick*; exit \$rc"
        } else {
            "exec $payloadRemote > $logPath 2>&1"
        }

        appendLog("[Exploit] Running CVE-2026-43499 for ${profile.name}…")
        if (!runPayload(launch, logPath)) {
            appendLog("[Error] Standalone exploit did not report its root-install success markers.")
            return "Failed"
        }

        appendLog("[Success] Temporary root acquired; staging UniRoot KernelSU…")
        val uniRootHelperRemote = "/data/local/tmp/uniroot-cve43499-root"
        val ksudRemote = "/data/local/tmp/ksud"
        val lateLoadStage = listOf(
            "cp ${quote(uniRootHelper.absolutePath)} ${quote(uniRootHelperRemote)}",
            "cp ${quote(ksud.absolutePath)} ${quote(ksudRemote)}",
            "cp ${quote(ksud.absolutePath)} /data/local/tmp/ksud-s25u-kdp",
            "cp ${quote(ksud.absolutePath)} /data/local/tmp/.ksud-stage",
            "chmod 755 ${quote(uniRootHelperRemote)} ${quote(ksudRemote)} /data/local/tmp/ksud-s25u-kdp /data/local/tmp/.ksud-stage",
        ).joinToString(" && ")
        val lateStageCode = runShizukuCommand(lateLoadStage)
        require(lateStageCode == 0) { "Unable to stage UniRoot KernelSU artifacts (rc=$lateStageCode)" }

        appendLog("[Daemon] Late-loading with UniRoot helper and classic Manager defaults…")
        val lateLoad = startShizukuProcess("exec ${quote(uniRootHelperRemote)} --late-load")
        val result = captureProcess(lateLoad, timeoutMillis = 180_000L)
        if (result.output.isNotBlank()) appendLog("[LATE] ${result.output}")

        var moduleLive = false
        for (attempt in 0 until 15) {
            if (kernelSuVisible()) {
                moduleLive = true
                break
            }
            delay(500)
        }
        require(moduleLive) {
            when {
                result.timedOut -> "KernelSU late-load timed out and no live module was detected"
                result.exitCode != 0 -> "KernelSU late-load failed (rc=${result.exitCode}) and no live module was detected"
                else -> "KernelSU late-load exited successfully, but no live module was detected"
            }
        }
        if (result.exitCode != 0) {
            appendLog("[!] Late-load process ended with rc=${result.exitCode}; live KernelSU module verified.")
        }
        if (!rootAlive()) {
            appendLog("[!] KernelSU is loaded, but UniRoot has no confirmed uid=0 grant yet.")
            appendLog("[!] Open the UniRoot-selected KernelSU Manager and authorize UniRoot.")
        }
        appendLog("[Pipeline] KernelSU module is live.")
        return "Success"
    }

    private suspend fun ksudForCurrentTarget(): File {
        DfKsudUpdater.currentTargetClassicKsud(context)?.let { return it }
        val targetKey = DfKsudUpdater.currentTargetKey(classic = true)
            ?: error("No UniRoot classic KernelSU target is configured for ${android.os.Build.MODEL}")
        appendLog("[KernelSU] Preparing UniRoot classic ksud for $targetKey…")
        val update = withContext(Dispatchers.IO) {
            DfKsudUpdater.run(
                context = context,
                classic = true,
                onLog = { appendLog("[KernelSU] $it") },
            )
        }
        appendLog("[KernelSU] $update")
        require(update.startsWith("OK:")) { "Unable to prepare UniRoot ksud: $update" }
        return DfKsudUpdater.currentTargetClassicKsud(context)
            ?: error("UniRoot updater did not produce a target-matched ksud for $targetKey")
    }

    private suspend fun runPayload(command: String, logPath: String): Boolean {
        check(runShizukuCommand("rm -f ${quote(logPath)}") == 0) {
            "Unable to clear the previous exploit log"
        }
        val process = startShizukuProcess(command)
        val startMillis = System.currentTimeMillis()
        var previousLog = ""
        var exitCode = -1
        var timedOut = false
        while (System.currentTimeMillis() - startMillis < 15 * 60_000L) {
            val log = captureShizukuOutput("cat ${quote(logPath)} 2>/dev/null")
            if (log.length > previousLog.length) {
                appendLog(log.substring(previousLog.length).trim())
                previousLog = log
            }
            val alive = withContext(Dispatchers.IO) {
                runCatching { process.isAlive }.getOrDefault(false)
            }
            if (!alive) {
                exitCode = withContext(Dispatchers.IO) {
                    runCatching { process.exitValue() }.getOrDefault(-1)
                }
                break
            }
            delay(250)
        }
        if (exitCode == -1 && withContext(Dispatchers.IO) {
                runCatching { process.isAlive }.getOrDefault(false)
            }) {
            timedOut = true
            process.destroyForcibly()
        }
        val finalLog = captureShizukuOutput("cat ${quote(logPath)} 2>/dev/null")
        if (finalLog.length > previousLog.length) {
            appendLog(finalLog.substring(previousLog.length).trim())
        }
        if (finalLog.isBlank()) appendLog("[!] Exploit log is empty — payload did not produce output.")
        if (timedOut) appendLog("[!] Standalone payload timed out after 15 minutes.")
        if (exitCode != 0) appendLog("[Exploit] Shizuku process exit code: $exitCode")
        return !timedOut && exploitSucceeded(finalLog)
    }

    private suspend fun captureProcess(process: Process, timeoutMillis: Long): ProcessResult =
        withContext(Dispatchers.IO) {
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            fun drain(input: java.io.InputStream, destination: StringBuilder, name: String) =
                Thread({
                    val text = runCatching { input.bufferedReader().use { it.readText() } }
                        .getOrDefault("")
                    synchronized(destination) { destination.append(text) }
                }, name).apply { isDaemon = true; start() }

            val stdoutThread = drain(process.inputStream, stdout, "uniroot-standalone-stdout")
            val stderrThread = drain(process.errorStream, stderr, "uniroot-standalone-stderr")
            val finished = runCatching { process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) }
                .getOrDefault(false)
            if (!finished) runCatching { process.destroyForcibly() }
            runCatching { stdoutThread.join(2_000) }
            runCatching { stderrThread.join(2_000) }
            val output = synchronized(stdout) { stdout.toString() } +
                synchronized(stderr) { stderr.toString() }
            val code = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -2
            ProcessResult(code, output.trim(), timedOut = !finished)
        }
}
