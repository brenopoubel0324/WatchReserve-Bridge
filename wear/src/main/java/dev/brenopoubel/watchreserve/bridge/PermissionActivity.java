package dev.brenopoubel.watchreserve.bridge;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import android.os.Bundle;
import android.widget.Toast;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuApiConstants;

/** Confirmação local explícita. O manifesto admite somente chamadas de shell/sistema. */
public final class PermissionActivity extends ComponentActivity {
    private int uid, pid, code;
    private boolean answered;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { reply(false, true); }
        });
        uid = getIntent().getIntExtra("uid", -1);
        pid = getIntent().getIntExtra("pid", -1);
        code = getIntent().getIntExtra("requestCode", -1);
        try {
            if (!Shizuku.pingBinder() || Shizuku.getUid() != 2000 || pid <= 0 || code < 0 ||
                !AllowedClient.PACKAGE.equals(getIntent().getStringExtra("packageName")) ||
                uid != AllowedClient.uid(getPackageManager())) throw new SecurityException("Pedido não reconhecido.");
        } catch (Exception e) {
            Toast.makeText(this, "Pedido de acesso não qualificado.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        WearUi ui = new WearUi(this, "Autorizar acesso");
        ui.body("WatchReserve Lab");
        ui.body("A ponte concede privilégios do shell ADB. Este laboratório usa apenas comandos de leitura.");
        ui.button("Permitir nesta sessão", v -> reply(true, true));
        ui.button("Permitir sempre", v -> reply(true, false));
        ui.button("Negar", v -> reply(false, true));
        ui.body("Você pode revogar o acesso na ponte.");
    }

    private void reply(boolean allowed, boolean once) {
        if (answered) return;
        try {
            if (uid != AllowedClient.uid(getPackageManager()) || Shizuku.getUid() != 2000) {
                throw new SecurityException("O cliente ou a ponte mudou.");
            }
            Bundle result = new Bundle();
            result.putBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED, allowed);
            result.putBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME, once);
            Shizuku.dispatchPermissionConfirmationResult(uid, pid, code, result);
            answered = true;
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível confirmar. Solicite acesso novamente.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

}
