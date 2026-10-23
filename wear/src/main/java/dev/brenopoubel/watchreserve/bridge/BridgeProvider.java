package dev.brenopoubel.watchreserve.bridge;

import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import moe.shizuku.api.BinderContainer;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.ShizukuProvider;

/** Adaptação do provider do Shevery; apenas entrega de Binder e UserService. */
public final class BridgeProvider extends ShizukuProvider {
    private static final String EXTRA = "moe.shizuku.privileged.api.intent.extra.BINDER";
    static { disableAutomaticSuiInitialization(); }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (extras == null) return null;
        extras.setClassLoader(BinderContainer.class.getClassLoader());
        @SuppressWarnings("deprecation") BinderContainer container = extras.getParcelable(EXTRA);
        if (METHOD_SEND_BINDER.equals(method)) {
            if (container == null || container.binder == null) return null;
            Shizuku.onBinderReceived(container.binder, getContext().getPackageName());
            return new Bundle();
        }
        if (!"sendUserService".equals(method)) return super.call(method, arg, extras);
        String token = extras.getString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN);
        if (token == null || container == null || container.binder == null) return null;
        IBinder userService = container.binder;
        // Binder vivo não significa SDK inicializado. Aguardar o callback de prontidão.
        // Handler temporário: não bloquear a thread que precisa receber o Binder.
        HandlerThread thread = new HandlerThread("bridge-attach");
        thread.start();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Bundle> result = new AtomicReference<>();
        AtomicBoolean pending = new AtomicBoolean(true);
        Shizuku.OnBinderReceivedListener listener = () -> {
            if (!pending.compareAndSet(true, false)) return;
            try { result.set(attach(userService, token)); }
            finally { ready.countDown(); }
        };
        try {
            Shizuku.addBinderReceivedListenerSticky(listener, new Handler(thread.getLooper()));
            return ready.await(5, TimeUnit.SECONDS) ? result.get() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            pending.set(false);
            Shizuku.removeBinderReceivedListener(listener);
            thread.quitSafely();
        }
    }

    private Bundle attach(IBinder service, String token) {
        try {
            Bundle options = new Bundle();
            options.putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, token);
            Shizuku.attachUserService(service, options);
            Bundle reply = new Bundle();
            reply.putParcelable(EXTRA, new BinderContainer(Shizuku.getBinder()));
            return reply;
        } catch (RuntimeException e) {
            Log.w("WatchReserveBridge", "Falha ao anexar helper", e);
            return null;
        }
    }
}
