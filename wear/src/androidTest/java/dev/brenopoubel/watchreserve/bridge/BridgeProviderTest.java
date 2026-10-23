package dev.brenopoubel.watchreserve.bridge;

import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import moe.shizuku.api.BinderContainer;
import moe.shizuku.server.IShizukuService;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuApiConstants;
import static org.junit.Assert.*;

/** Corrida controlada do SDK; não inicia servidor nem executa comandos shell. */
@RunWith(AndroidJUnit4.class)
public final class BridgeProviderTest {
    private static final String EXTRA = "moe.shizuku.privileged.api.intent.extra.BINDER";

    @BeforeClass public static void guard() {
        BridgeWearTest.guard();
        assertFalse("Execute esta regressão sem ponte ativa.", Shizuku.pingBinder());
    }

    @Test public void liveBinderWaitsForSdkReadinessAndAttachesOnlyOnce() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (SdkFixture sdk = new SdkFixture()) {
            assertTrue(Shizuku.pingBinder());
            int listeners = sdk.listenerCount();
            Future<Bundle> reply = executor.submit(() -> new BridgeProvider().call("sendUserService", null, request()));
            long deadline = SystemClock.elapsedRealtime() + 2000;
            while (sdk.listenerCount() == listeners && !reply.isDone() && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(10);
            }
            assertEquals("A conexão precisa aguardar a inicialização do SDK.", listeners + 1, sdk.listenerCount());
            assertFalse("Binder vivo não comprova prontidão.", reply.isDone());
            assertEquals(0, sdk.attached.get());
            sdk.ready();
            sdk.notifyReady(); // O SDK admite mais de uma notificação.
            assertNotNull(reply.get(3, TimeUnit.SECONDS));
            assertEquals(1, sdk.attached.get());
            assertEquals(listeners, sdk.listenerCount());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test public void initializedSdkUsesTheSameHandshake() throws Exception {
        try (SdkFixture sdk = new SdkFixture()) {
            sdk.ready();
            assertNotNull(new BridgeProvider().call("sendUserService", null, request()));
            assertEquals(1, sdk.attached.get());
        }
    }

    @Test public void timeoutRemovesListenerAndDoesNotAttachLater() throws Exception {
        try (SdkFixture sdk = new SdkFixture()) {
            int listeners = sdk.listenerCount();
            assertNull(new BridgeProvider().call("sendUserService", null, request()));
            assertEquals(listeners, sdk.listenerCount());
            sdk.ready();
            assertEquals(0, sdk.attached.get());
        }
    }

    @Test public void registrationDoesNotLoseReadinessWhileWaitingForListenerLock() throws Exception {
        CountDownLatch notified = new CountDownLatch(1);
        Shizuku.OnBinderReceivedListener listener = notified::countDown;
        Thread subscriber = new Thread(() -> Shizuku.addBinderReceivedListenerSticky(
            listener, new Handler(Looper.getMainLooper())), "fixture-subscribe");
        try (SdkFixture sdk = new SdkFixture()) {
            try {
                synchronized (sdk.listeners()) {
                    subscriber.start();
                    long deadline = SystemClock.elapsedRealtime() + 2000;
                    while (subscriber.getState() != Thread.State.BLOCKED && SystemClock.elapsedRealtime() < deadline) {
                        SystemClock.sleep(10);
                    }
                    assertEquals("A inscrição precisa disputar o lock com a notificação.",
                        Thread.State.BLOCKED, subscriber.getState());
                    sdk.ready();
                }
                subscriber.join(2000);
                assertFalse(subscriber.isAlive());
                assertTrue("A prontidão não pode se perder antes da inscrição.", notified.await(2, TimeUnit.SECONDS));
            } finally {
                subscriber.join(2000);
                Shizuku.removeBinderReceivedListener(listener);
            }
        }
    }

    private static Bundle request() {
        Bundle request = new Bundle();
        request.putParcelable(EXTRA, new BinderContainer(new Binder()));
        request.putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, "fixture-only");
        return request;
    }

    /** Simula o intervalo entre publicar binder e inicializar service, sem binder remoto. */
    private static final class SdkFixture implements AutoCloseable {
        final AtomicInteger attached = new AtomicInteger();
        private final Field binder = field("binder"), service = field("service"), ready = field("binderReady");
        private final Object oldBinder = binder.get(null), oldService = service.get(null), oldReady = ready.get(null);
        private final Binder live = new Binder();

        SdkFixture() throws Exception {
            ready.set(null, false);
            service.set(null, null);
            binder.set(null, live);
        }

        void ready() throws Exception {
            service.set(null, Proxy.newProxyInstance(IShizukuService.class.getClassLoader(),
                new Class<?>[]{IShizukuService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("asBinder")) return live;
                    if (method.getName().equals("attachUserService")) {
                        attached.incrementAndGet();
                        return null;
                    }
                    throw new AssertionError("Chamada inesperada: " + method.getName());
                }));
            ready.set(null, true);
            notifyReady();
        }

        void notifyReady() throws Exception {
            Method dispatch = Shizuku.class.getDeclaredMethod("scheduleBinderReceivedListeners");
            dispatch.setAccessible(true);
            dispatch.invoke(null);
        }

        int listenerCount() throws Exception {
            List<?> listeners = listeners();
            synchronized (listeners) { return listeners.size(); }
        }

        List<?> listeners() throws Exception { return (List<?>) field("RECEIVED_LISTENERS").get(null); }

        @Override public void close() throws Exception {
            ready.set(null, oldReady);
            service.set(null, oldService);
            binder.set(null, oldBinder);
        }

        private static Field field(String name) throws Exception {
            Field field = Shizuku.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }
    }
}
