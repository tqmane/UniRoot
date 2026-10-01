# Third-party notices

The A059 DirtyFrag page-cache preflight under `app/src/main/jni/` is based on
DFRoot by diabl0w, pinned to commit `3a6964aadc5586802caeb77d2f4de54df3528c5e`.
UniRoot adds the crash_dump64 page-cache read-back check and an A059-only JNI
preflight; it does not replace the existing prebuilt root engine. See
https://github.com/diabl0w/DFRoot for upstream attribution and notices.

The standalone CVE-2026-43499 payloads and temporary-root helpers under
`app/src/main/assets/profiles/nothing-a059/` and
`app/src/main/assets/profiles/oneplus-pad3/` were imported from the matching
device projects in [Root-My-Device](https://github.com/tqmane/Root-My-Device).
Their upstream notices trace the payload lineage to
[Root-My-Galaxy-Payloads](https://github.com/BuSung-dev/Root-My-Galaxy-Payloads)
by BuSung-dev and the
[CyberMeowfia CVE-2026-43499 research](https://github.com/NebuSec/CyberMeowfia/tree/main/IonStack/CVE-2026-43499/exploit)
by NebuSec. Root-My-Device-Payloads by WitAqua-tools is also listed there as an
upstream source. Each binary retains the license terms and notices applicable
to its source; consult the upstream repositories before redistributing the
APK.

This integration does **not** include the Root-My-Device `ksud`, KernelSU
module, Manager APK, or `org.witaqua.pwn.kernelsu` package. KernelSU loading
uses UniRoot's updater, helper, and normal Manager package instead.
