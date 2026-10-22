package dev.brenopoubel.watchreserve.bridge;

import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;
import org.junit.BeforeClass;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.Properties;
import static org.junit.Assert.*;
import rikka.shizuku.Shizuku;

/** Autorizações reais na tela circular, sem modificar o tamanho físico do AVD. */
@RunWith(AndroidJUnit4.class)
public final class BridgeWearTest {
    private final UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    private static final String LAB = "dev.brenopoubel.watchreserve.advancedlab";
    private boolean handoff;

    @BeforeClass public static void guard() {
        assertEquals("WatchReserve_Wear5_Small_Round", InstrumentationRegistry.getArguments().getString("reservedAvd"));
        assertTrue(Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish"));
        assertTrue(InstrumentationRegistry.getInstrumentation().getTargetContext().getResources().getConfiguration().isScreenRound());
    }

    @Test public void denyOnceAlwaysRevokeAndReadOnRoundScreen() throws Exception {
        assertTrue("A ponte deve estar iniciada.", Shizuku.pingBinder());
        assertEquals(2000, Shizuku.getUid());
        int uid = AllowedClient.uid(InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageManager());
        Shizuku.updateFlagsForUid(uid, 6, 0);
        openLab();
        click("Autorizar acesso");
        assertTrue(device.wait(Until.hasObject(By.text("A ponte concede privilégios do shell ADB. Este laboratório usa apenas comandos de leitura.")), 5000));
        capture("authorization-top");
        click("Negar");
        expectText("Acesso recusado. Nenhuma coleta iniciada.");
        assertEquals(0, Shizuku.getFlagsForUid(uid, 6) & 2);
        click("Autorizar acesso");
        assertTrue(device.wait(Until.hasObject(By.pkg("dev.brenopoubel.watchreserve.bridge").text("Autorizar acesso")), 5000));
        device.pressBack();
        expectText("Acesso recusado. Nenhuma coleta iniciada.");
        click("Autorizar acesso");
        click("Permitir nesta sessão");
        expectText("WatchReserve Bridge autorizado.");
        assertEquals("A autorização desta sessão não deve persistir.", 0, Shizuku.getFlagsForUid(uid, 6) & 2);

        // Matar o cliente remove a autorização de sessão; a ponte permanece ativa.
        shell("am force-stop " + LAB);
        openLab();
        click("Autorizar acesso");
        scrollTo("Permitir sempre");
        capture("authorization-actions");
        click("Permitir sempre");
        expectText("WatchReserve Bridge autorizado.");
        assertEquals(2, Shizuku.getFlagsForUid(uid, 6) & 2);
        shell("am force-stop " + LAB);
        openLab();
        expectText("WatchReserve Bridge autorizado.");
        click("Leitura rápida");
        expectText("UID 2000 · 2 consultas. 0 parciais ou com falha.");
        assertTrue(device.hasObject(By.textContains("0 parciais ou com falha.")));
        shell("am start -n dev.brenopoubel.watchreserve.bridge/.BridgeActivity");
        click("Revogar laboratório");
        assertEquals(0, Shizuku.getFlagsForUid(uid, 6) & 2);
        openLab();
        click("Autorizar acesso");
        click("Permitir sempre");
        expectText("WatchReserve Bridge autorizado.");
        shell("am start -n dev.brenopoubel.watchreserve.bridge/.BridgeActivity");
        assertTrue(device.wait(Until.hasObject(By.pkg("dev.brenopoubel.watchreserve.bridge")
            .textContains("Ativa · shell UID 2000")), 5000));
        capture("bridge-status");
    }

    private void openLab() throws Exception {
        shell("am start -n " + LAB + "/.ui.LabActivity");
        assertTrue(device.wait(Until.hasObject(By.text("Somente leitura")), 5000));
    }

    @Test public void stopButtonEndsServerAndHelpers() throws Exception {
        shell("am start -n dev.brenopoubel.watchreserve.bridge/.BridgeActivity");
        click("Parar ponte");
        expectText("Ponte parada. Inicie pelo ADB no PC.");
        Thread.sleep(1500);
        String processes = shell("ps -A -o NAME");
        assertFalse("Servidor persistiu após parar.", processes.contains("watchreserve_bridge"));
        assertFalse("Helper persistiu após parar.", processes.contains(LAB + ":diagnostic"));
        assertFalse("Supervisor nativo inesperado.", processes.contains("libshizuku.so"));
    }

    @Test public void prepareBoundLocalSessionForTransportHandoff() throws Exception {
        handoff = true;
        startPreparedRead();
        assertTrue("O helper deve existir antes da perda de ADB.", shell("ps -A -o NAME").contains(LAB + ":diagnostic"));
    }

    @Test public void clientDeathRevocationAndBridgeDeathReconcileLocally() throws Exception {
        int uid = AllowedClient.uid(InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageManager());
        startPreparedRead();
        String killedId = recipe().getProperty("id");
        shell("am force-stop " + LAB);
        openLab();
        awaitPhase("INTERRUPTED");
        assertEquals(killedId, recipe().getProperty("id"));
        assertNoHelper();

        startPreparedRead();
        String revokedId = recipe().getProperty("id");
        Shizuku.updateFlagsForUid(uid, 6, 0);
        openLab();
        awaitPhase("INTERRUPTED");
        assertEquals(revokedId, recipe().getProperty("id"));
        assertNoHelper();
        click("Autorizar acesso");
        click("Permitir sempre");
        expectText("WatchReserve Bridge autorizado.");

        startPreparedRead();
        String bridgeId = recipe().getProperty("id");
        String pid = null;
        for (String line : shell("ps -A -o USER,PID,NAME").split("\\r?\\n")) {
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("^shell\\s+(\\d+)\\s+watchreserve_bridge$").matcher(line);
            if (match.matches()) { assertNull("Mais de um servidor inesperado.", pid); pid = match.group(1); }
        }
        assertNotNull("Servidor shell não encontrado.", pid);
        shell("kill -9 " + pid);
        awaitPhase("FAILED");
        assertEquals(bridgeId, recipe().getProperty("id"));
        assertNoHelper();
        assertFalse(Shizuku.pingBinder());
        android.os.SystemClock.sleep(1500);
        assertFalse("Supervisor reiniciou a ponte.", shell("ps -A -o NAME").contains("watchreserve_bridge"));
    }

    @Test public void restartedBridgeAllowsNewReadWithoutClearingData() throws Exception {
        openLab();
        expectText("WatchReserve Bridge autorizado.");
        click("Leitura rápida");
        expectText("UID 2000 · 2 consultas. 0 parciais ou com falha.");
        awaitPhase("COMPLETED");
        assertNoHelper();
    }

    private void startPreparedRead() throws Exception {
        openLab();
        click("Preparar sessão offline");
        awaitPhase("PREPARED");
        scrollTo("Executar sessão");
        android.os.SystemClock.sleep(400);
        device.findObject(By.text("Executar sessão")).click();
        awaitPhase("DELAYED");
    }

    private Properties recipe() throws IOException {
        Properties values = new Properties();
        values.load(new StringReader(shell("run-as " + LAB + " cat no_backup/read-session.properties")));
        return values;
    }

    private void awaitPhase(String phase) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (phase.equals(recipe().getProperty("phase"))) return;
            android.os.SystemClock.sleep(100);
        }
        assertEquals("Fase persistida não observada.", phase, recipe().getProperty("phase"));
    }

    private void assertNoHelper() throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            if (!shell("ps -A -o NAME").contains(LAB + ":diagnostic")) return;
            android.os.SystemClock.sleep(100);
        }
        fail("Helper permaneceu depois da interrupção.");
    }

    private void scrollTo(String text) throws Exception {
        device.waitForIdle();
        if (!device.hasObject(By.text(text))) {
            new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().text(text));
        }
        // Compose pode terminar a busca antes de publicar os nós após uma rolagem.
        for (int attempt = 0; attempt < 8 && !device.hasObject(By.text(text)); attempt++) {
            device.swipe(device.getDisplayWidth()/2, device.getDisplayHeight()*3/4,
                device.getDisplayWidth()/2, device.getDisplayHeight()/3, 20);
            device.waitForIdle();
            android.os.SystemClock.sleep(200);
        }
        assertTrue("Controle não alcançável: " + text,
            device.wait(Until.hasObject(By.text(text)), 2000));
        // Um nó parcialmente exposto no limite inferior não é um alvo seguro.
        for (int attempt = 0; attempt < 5; attempt++) {
            android.graphics.Rect bounds = device.findObject(By.text(text)).getVisibleBounds();
            if (bounds.centerY() > device.getDisplayHeight() * 0.78) {
                device.swipe(device.getDisplayWidth()/2, device.getDisplayHeight()*3/4,
                    device.getDisplayWidth()/2, device.getDisplayHeight()/3, 15);
            } else if (bounds.centerY() < device.getDisplayHeight() * 0.20) {
                device.swipe(device.getDisplayWidth()/2, device.getDisplayHeight()/3,
                    device.getDisplayWidth()/2, device.getDisplayHeight()*2/3, 15);
            } else return;
            device.waitForIdle();
        }
    }

    private void expectText(String text) throws Exception {
        device.wait(Until.hasObject(By.text(text)), 3000);
        scrollTo(text);
    }

    private void click(String text) throws Exception {
        scrollTo(text);
        // A animação Compose de retorno ao topo pode sobreviver ao idle de a11y.
        android.os.SystemClock.sleep(400);
        assertNotNull("Controle ausente: " + text, device.findObject(By.text(text)));
        device.findObject(By.text(text)).clickAndWait(Until.newWindow(), 2000);
        device.waitForIdle();
    }

    private void capture(String name) {
        android.os.SystemClock.sleep(1000); // A superfície de splash pode sobreviver à árvore de a11y.
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(), "review-screenshots");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        assertTrue(device.takeScreenshot(new File(directory, name + ".png")));
    }

    private String shell(String command) throws IOException { return device.executeShellCommand(command); }

    @After public void captureFinalState() throws Exception {
        if (handoff) return; // Devolver o ADB ao PC durante os cinco segundos de preparação.
        capture("last-state");
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(), "review-screenshots");
        device.dumpWindowHierarchy(new File(directory, "last-ui.xml"));
    }
}
