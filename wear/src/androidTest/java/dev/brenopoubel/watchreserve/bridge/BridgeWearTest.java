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
import static org.junit.Assert.*;
import rikka.shizuku.Shizuku;

/** Autorizações reais na tela circular, sem modificar o tamanho físico do AVD. */
@RunWith(AndroidJUnit4.class)
public final class BridgeWearTest {
    private final UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    private static final String LAB = "dev.brenopoubel.watchreserve.advancedlab";

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

    private void scrollTo(String text) throws Exception {
        device.waitForIdle();
        if (!device.hasObject(By.text(text))) {
            new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().text(text));
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
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(), "review-screenshots");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        assertTrue(device.takeScreenshot(new File(directory, name + ".png")));
    }

    private String shell(String command) throws IOException { return device.executeShellCommand(command); }

    @After public void captureFinalState() throws Exception {
        capture("last-state");
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(), "review-screenshots");
        device.dumpWindowHierarchy(new File(directory, "last-ui.xml"));
    }
}
