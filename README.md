# UniRoot

**One-tap root — New exploit (DirtyFrag CVE-2026-43284, unprivileged) + Old exploit profiles, with auto-root at every boot. Samsung Galaxy + Nothing Phone (3a) + OnePlus Pad 3.**

Made by **kuuky**.

<p align="center"><b>I am not responsible for bricked phones.</b></p>

---

## What's new in V5

- **New exploit (fast)** — the unprivileged DirtyFrag engine (CVE-2026-43284): IpSecManager allocates the SA, the native engine corrupts the kernel page cache, ksud is late-loaded from memory. No profile, no Shizuku, no setup — press **Root now**.
- **ksud updater, in the app** — a full-width button (New exploit section) downloads the **latest official ksud** (KernelSU Next *or* classic, per the selected flavor) and patches it **on device** with the bundled **Samsung KDP+DEFEX .kos**, then the result is used immediately (Next → auto-selected profile, classic → `ksud-classic-latest`). Already updated? → *"you are already updated"*. On Nothing A059 / OnePlus OPD2415 the device's own KMI slot (`android14-6.1` / `android15-6.6`) uses the matching upstream ko from the selected KernelSU flavor instead.
- **ksud profiles** — profile bands (same style as the old exploit profiles) for both flavors: bundled + downloaded profiles, selectable, and deletable from **Advanced settings**.
- **Auto-root at boot** — a foreground boot service waits for the first unlock and re-runs the chain (up to 6 attempts), with a partial WakeLock so the phone can't sleep mid-exploit.
- **Progress UI** — animated progress bar with live engine stages (IpSec SA → SPI → ksud staged → arming → **Phone rooted ✓**), and a bottom progress popup for the ksud updater. Fully in English.
- **Root state banner** — clean Rooted / Not rooted banner driven by a live `su -c id` probe (no more false "rooted" after a reboot).

## Old exploit (slow)

The classic profile pipeline stays intact: CVE-2026-43499 payloads (local or Shizuku), Root My Galaxy integration, the existing device profiles (S25 ZZHL/ZZI4, S26 Ultra ZZHK, S93XX, Oppo), profile editor, instant root, and run-log archives. Nothing Phone (3a) A059 and OnePlus Pad 3 OPD2415 are added as separate standalone Shizuku profiles.

Nothing A059 (kernel `6.1.157-android14-11`, KMI `android14-6.1`) and OnePlus Pad 3 OPD2415 (kernel `6.6.118-android15-8`, KMI `android15-6.6`) are added to Old exploit as Shizuku standalone profiles; Nothing uses its page-cache kicker. Only the target exploit payload and its temporary-root helper are imported; the RMD exact-build guards and receipt architecture are not. KernelSU loading uses UniRoot's own helper, classic Manager package (`me.weishu.kernelsu`), and updater-generated ksud. The first Old exploit run prepares a target-matched classic ksud online and caches it for that model/KMI. DirtyFrag prepares the selected flavor the same way; its target KMI slot uses the matching upstream release ko, while other slots retain the Samsung-specific modules. These new UniRoot root chains have not yet been device-verified end to end.

Install UniRoot's normal KernelSU classic Manager (`me.weishu.kernelsu`) and authorize UniRoot for `su`; no Root-My-Device Manager package or signer pairing is used. UniRoot does not bundle the Manager APK.

## KernelSU Manager and signing

- **Classic:** install the official Manager from [KernelSU releases](https://github.com/tiann/KernelSU/releases); package `me.weishu.kernelsu`.
- **Next:** install the official Manager from [KernelSU-Next releases](https://github.com/KernelSU-Next/KernelSU-Next/releases); package `com.rifsxd.ksunext`.
- Keep the upstream Manager APK signature; do not re-sign it. The matching upstream ksud/module expects that Manager identity. After loading root, grant `com.example.universalsystemporter` superuser access in the selected Manager.
- UniRoot's current Gradle `release` build also uses the debug keystore (`app/build.gradle.kts`). That's suitable for local builds only; a distributed APK needs a private release key, and future updates must keep using that same key. UniRoot's APK signing key is separate from the KernelSU Manager's signing identity.

## Build

```sh
./gradlew assembleRelease   # -> app/build/outputs/apk/release/
```

## Credits

This app stands on the shoulders of:

- **diabl0w** — [DFRoot](https://github.com/diabl0w/DFRoot): the DirtyFrag engine this whole project is built around
- **snothin** — [GhostSam](https://github.com/snothin/GhostSam): the Samsung-patched kos, the ksud patching approach and the staged native API
- **polygraphene** — the DFRoot fork lineage and the DEFEX bypass work
- **tiann** — [KernelSU](https://github.com/tiann/KernelSU)
- **rifsxd & the KernelSU-Next team** — [KernelSU-Next](https://github.com/KernelSU-Next/KernelSU-Next)
- **YuKongA** — [GhostLock](https://github.com/YuKongA/GhostLock): the UI this app's interface is based on
- **Root-My-Device** — the Nothing A059 and OnePlus Pad 3 standalone CVE-2026-43499 payload/helper artifacts; see [third-party notices](THIRD_PARTY_NOTICES.md)

Thank you all 🙏
