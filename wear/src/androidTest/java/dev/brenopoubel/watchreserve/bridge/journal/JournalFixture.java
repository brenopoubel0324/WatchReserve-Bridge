package dev.brenopoubel.watchreserve.bridge.journal;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import dev.brenopoubel.watchreserve.bridge.BuildConfig;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;
import static dev.brenopoubel.watchreserve.bridge.journal.RestorationJournal.*;
import static dev.brenopoubel.watchreserve.bridge.journal.JournalController.*;

/** Único backend de escrita deste protótipo: fixture fixa, apenas na instrumentação AVD. */
final class JournalFixture implements Backend {
    static final String PACKAGE = "dev.brenopoubel.watchreserve.lab";
    static final String TARGET = "package:" + PACKAGE + ":0";
    private static final String CERT = "caed7e1e5f17eead9720810079e0072264358d3219d72f67a0ea19466ec48d69";
    final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    final PackageManager pm = context.getPackageManager();
    final String frozenIdentity;
    boolean denied, dieAfterWrite;
    int writes;

    JournalFixture() throws Exception {
        assertTrue(BuildConfig.DEBUG);
        assertEquals("WatchReserve_Wear5_Small_Round", InstrumentationRegistry.getArguments().getString("reservedAvd"));
        assertTrue(Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish"));
        assertTrue(context.getResources().getConfiguration().isScreenRound());
        assertTrue(pm.hasSystemFeature(PackageManager.FEATURE_WATCH));
        assertEquals(192, context.getResources().getConfiguration().screenWidthDp);
        assertEquals(context.getApplicationInfo().uid, Process.myUid());
        assertTrue(Process.myUid() >= 10_000 && Process.myUid() < 100_000);
        assertEquals("dev.brenopoubel.watchreserve.bridge", context.getPackageName());
        assertEquals("db38593b18806c7403cc7c2950848d2ad6296c3d7010d35f58d80c37b8e1f228", certificate(context.getPackageName()));
        frozenIdentity = identity();
    }

    @SuppressWarnings("deprecation") private String identity() throws Exception {
        PackageInfo info = pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
        assertEquals(1, info.getLongVersionCode()); assertEquals("0.1-lab", info.versionName);
        assertEquals(CERT, certificate(PACKAGE));
        assertTrue(info.applicationInfo.uid >= 10_000 && info.applicationInfo.uid < 100_000);
        assertArrayEquals(new String[]{PACKAGE}, pm.getPackagesForUid(info.applicationInfo.uid));
        assertEquals(PackageManager.PERMISSION_DENIED, pm.checkPermission("android.permission.WRITE_SECURE_SETTINGS", PACKAGE));
        return info.applicationInfo.uid + ":1:" + CERT;
    }

    @SuppressWarnings("deprecation") private String certificate(String name) throws Exception {
        PackageInfo info = pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES);
        assertNotNull(info.signingInfo); assertFalse(info.signingInfo.hasMultipleSigners());
        assertEquals(1, info.signingInfo.getApkContentsSigners().length);
        return hash(info.signingInfo.getApkContentsSigners()[0].toByteArray());
    }

    @SuppressLint("HardwareIds") // Somente hash privado de qualificação do fixture, sem exportação.
    String environment() throws Exception {
        String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        assertNotNull(id); assertFalse(id.isEmpty());
        return hash((id + "|" + Build.FINGERPRINT + "|" + context.getPackageName() + "|" + Process.myUid()
            + "|" + BuildConfig.VERSION_CODE + "|" + certificate(context.getPackageName())).getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    @Override public void requireIdle() { if (denied) throw new SecurityException("Acesso de fixture indisponível."); }
    @Override public Observation observe(String target) throws Exception {
        requireIdle(); assertEquals(TARGET, target);
        return new Observation(identity(), Integer.toString(pm.getApplicationEnabledSetting(PACKAGE)));
    }
    @Override public void write(String target, String value) throws Exception {
        requireIdle(); assertEquals(TARGET, target); assertEquals(frozenIdentity, identity());
        String verb;
        if ("0".equals(value)) verb = "default-state";
        else if ("1".equals(value)) verb = "enable";
        else if ("2".equals(value)) verb = "disable";
        else if ("3".equals(value)) verb = "disable-user";
        else if ("4".equals(value)) verb = "disable-until-used";
        else throw new IllegalArgumentException("Estado de fixture não permitido.");
        writes++;
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .executeShellCommand("cmd package " + verb + " --user 0 " + PACKAGE);
        if (dieAfterWrite) {
            assertEquals(value, observe(target).value);
            Process.killProcess(Process.myPid());
            throw new AssertionError("A morte real do processo não ocorreu.");
        }
    }

    File root() { return new File(context.getNoBackupFilesDir(), "journal-fixtures"); }
    AtomicJournalStore store(String name) {
        assertTrue(name.equals("crash") || UUID.fromString(name).toString().equals(name));
        File directory = new File(root(), name); assertTrue(directory.isDirectory() || directory.mkdirs());
        return new AtomicJournalStore(new ContextWrapper(context) {
            @Override public File getNoBackupFilesDir() { return directory; }
        });
    }
    JournalController controller(AtomicJournalStore store) throws Exception { return new JournalController(store, this, environment()); }
    List<Change> change(String value) { return List.of(new Change(TARGET, value)); }

    /** Ordem conhecida, sem adivinhar originais ou apagar um diário ilegível/conflitante. */
    void recoverAll(boolean requireCrashEvidence) throws Exception {
        File[] directories = root().listFiles();
        if (directories == null) { assertFalse(requireCrashEvidence); return; }
        assertTrue(directories.length <= 32);
        if (requireCrashEvidence) {
            RestorationJournal crash = store("crash").load(Kind.PROFILE);
            assertNotNull(crash); assertEquals(State.INTENDED, crash.entries.get(0).state);
            assertEquals("3", observe(TARGET).value);
        }
        for (File directory : directories) {
            assertEquals(root().getCanonicalFile(), directory.getCanonicalFile().getParentFile());
            AtomicJournalStore store = store(directory.getName()); JournalController controller = controller(store);
            for (Kind kind : List.of(Kind.RECOVERY, Kind.PROFILE, Kind.PREPARATION)) {
                RestorationJournal journal = store.load(kind);
                if (journal != null && !journal.closed) {
                    assertTrue("Recuperação de fixture pendente: " + kind, controller.restore(kind, journal.id).closed);
                    assertEquals(journal.entries.get(0).original, observe(TARGET).value);
                }
            }
        }
        // Apenas arquivos conhecidos de fixture, depois de todos os diários fecharem.
        assertNoPending();
        for (File directory : directories) {
            File storage = new File(directory, "restoration");
            for (Kind kind : Kind.values()) new android.util.AtomicFile(new File(storage,
                kind.name().toLowerCase(java.util.Locale.ROOT) + ".properties")).delete();
            assertTrue(storage.delete()); assertTrue(directory.delete());
        }
    }

    void assertNoPending() throws Exception {
        File[] directories = root().listFiles(); if (directories == null) return;
        for (File directory : directories) for (Kind kind : Kind.values()) {
            RestorationJournal journal = store(directory.getName()).load(kind);
            assertTrue("Recupere o fixture antes de novo ensaio.", journal == null || journal.closed);
        }
    }
}
