package com.uniroot.app.engine

data class DeviceProfile(
    val name: String, val kaslrOffset: String, val pathSo: String, val pathKo: String, val pathKsud: String,
    val deviceType: String, val pathCveNormal: String?, val pathCveRoot: String?,
    /** "kernelsu" (default) or "kernelsu_next" — drives the home-page switch + manager relaunch. */
    val flavor: String = "kernelsu",
    /** Run this profile's payload through the Shizuku shell (UID 2000), like S26 Ultra / Z Fold 8. */
    val useShizuku: Boolean = false,
    /** Standalone PIE payload (Root-My-Device style): executed directly, not via LD_PRELOAD. */
    val standalone: Boolean = false,
)

data class DeviceInfo(
    val model: String,
    val kernel: String,
    val soc: String,
    val matchedProfileName: String?,
)

/** One entry of the Root My Galaxy support feed (targets-v3.json). */
data class RmgTarget(
    val payloadId: String,
    val displayName: String,
    val models: List<String>,
    val kernelVersions: List<String>,
    val exploitUrl: String,
    val exploitSize: Long,
    val ksudUrl: String,
    val ksudSize: Long,
)

/** One saved run log box (Root My Galaxy style): timestamp + final status, shareable as .txt. */
data class RunLogFile(
    val file: java.io.File,
    val timeMillis: Long,
    val status: String,
)
