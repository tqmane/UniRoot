# Third-party notices

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
