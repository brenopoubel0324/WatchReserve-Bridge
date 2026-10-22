package dev.brenopoubel.watchreserve.bridge;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import rikka.shizuku.Shizuku;

public final class BridgeActivity extends Activity {
    private TextView status;
    private Button stop, revoke;
    private final Shizuku.OnBinderReceivedListener received = () -> runOnUiThread(this::refresh);
    private final Shizuku.OnBinderDeadListener dead = () -> runOnUiThread(this::refresh);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        WearUi ui = new WearUi(this, "WatchReserve");
        ui.body("Ponte · laboratório");
        status = ui.body("Verificando acesso…");
        ui.button("Atualizar estado", v -> refresh());
        revoke = ui.button("Revogar laboratório", v -> {
            try {
                Shizuku.updateFlagsForUid(AllowedClient.uid(getPackageManager()), 6, 0);
                Toast.makeText(this, "Acesso revogado.", Toast.LENGTH_SHORT).show();
            } catch (Exception e) { message("Não foi possível revogar o acesso."); }
            refresh();
        });
        stop = ui.button("Parar ponte", v -> {
            try { Shizuku.exit(); } catch (RuntimeException e) { message("Confira o estado da ponte."); }
            refresh();
        });
        ui.body("A partida desta versão é feita por ADB no PC. Após reiniciar, a ponte precisa ser iniciada novamente.");
        ui.body("Sem watchdog, atualização automática ou reconexão em segundo plano. A ponte ativa mantém um processo shell.");
        Shizuku.addBinderReceivedListenerSticky(received);
        Shizuku.addBinderDeadListener(dead);
    }

    @Override public void onResume() { super.onResume(); refresh(); }

    private void refresh() {
        if (status == null) return;
        boolean available = false;
        String text = "Ponte parada. Inicie pelo ADB no PC.";
        try {
            available = Shizuku.pingBinder() && Shizuku.getUid() == 2000;
            if (available) {
                text = "Ativa · shell UID 2000\nAPI " + Shizuku.getVersion();
                try {
                    boolean granted = (Shizuku.getFlagsForUid(AllowedClient.uid(getPackageManager()), 6) & 2) != 0;
                    text += granted ? "\nLaboratório autorizado" : "\nAutorização persistente ausente";
                } catch (Exception e) { text += "\nCliente não qualificado ou ausente"; }
            }
        } catch (RuntimeException e) { text = "Conexão perdida. Atualize o estado."; }
        status.setText(text);
        stop.setEnabled(available);
        revoke.setEnabled(available);
    }

    private void message(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    @Override public void onDestroy() {
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(dead);
        super.onDestroy();
    }
}
