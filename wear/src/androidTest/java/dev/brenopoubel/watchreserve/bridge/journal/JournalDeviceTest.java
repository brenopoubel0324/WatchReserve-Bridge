package dev.brenopoubel.watchreserve.bridge.journal;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import static org.junit.Assert.*;
import static dev.brenopoubel.watchreserve.bridge.journal.RestorationJournal.*;

@RunWith(AndroidJUnit4.class)
public final class JournalDeviceTest {
    private JournalFixture fixture;
    private String before;
    @Before public void prepare() throws Exception {
        fixture = new JournalFixture(); fixture.assertNoPending(); before = fixture.observe(JournalFixture.TARGET).value;
        assertTrue(before.equals("0") || before.equals("1"));
    }

    @Test public void realPackageRestoresThreeNestedOriginalsIncludingDefaultAndEnabled() throws Exception {
        AtomicJournalStore store = fixture.store(UUID.randomUUID().toString()); JournalController controller = fixture.controller(store);
        String prepared = before.equals("0") ? "1" : "0";
        RestorationJournal preparation = controller.prepare(Kind.PREPARATION, fixture.change(prepared));
        controller.apply(preparation.kind, preparation.id);
        RestorationJournal profile = controller.prepare(Kind.PROFILE, fixture.change("3"));
        controller.apply(profile.kind, profile.id);
        assertFalse(fixture.pm.getApplicationInfo(JournalFixture.PACKAGE, 0).enabled);
        RestorationJournal recovery = controller.prepare(Kind.RECOVERY, fixture.change(prepared));
        controller.apply(recovery.kind, recovery.id);
        assertThrows(IllegalArgumentException.class, () -> controller.reconcile(profile.kind, profile.id));
        assertThrows(IllegalArgumentException.class, () -> controller.restore(preparation.kind, preparation.id));
        assertEquals(before, store.load(Kind.PREPARATION).entries.get(0).original);
        assertEquals(prepared, store.load(Kind.PROFILE).entries.get(0).original);
        assertEquals("3", store.load(Kind.RECOVERY).entries.get(0).original);
        assertTrue(controller.restore(recovery.kind, recovery.id).closed); assertEquals("3", fixture.observe(JournalFixture.TARGET).value);
        assertTrue(controller.restore(profile.kind, profile.id).closed); assertEquals(prepared, fixture.observe(JournalFixture.TARGET).value);
        assertTrue(controller.restore(preparation.kind, preparation.id).closed); assertEquals(before, fixture.observe(JournalFixture.TARGET).value);
        assertTrue(fixture.pm.getApplicationInfo(JournalFixture.PACKAGE, 0).enabled);
    }

    @Test public void unavailableBackendKeepsAppliedStateAndOriginalForLaterRecovery() throws Exception {
        String name = UUID.randomUUID().toString();
        AtomicJournalStore store = fixture.store(name); JournalController controller = fixture.controller(store);
        RestorationJournal profile = controller.prepare(Kind.PROFILE, fixture.change("3")); controller.apply(profile.kind, profile.id);
        fixture.denied = true;
        assertThrows(SecurityException.class, () -> controller.restore(profile.kind, profile.id));
        assertFalse(store.load(profile.kind).closed); assertEquals(before, store.load(profile.kind).entries.get(0).original);
        assertEquals("3", new JournalFixture().observe(JournalFixture.TARGET).value);
        fixture.denied = false;
        assertTrue(fixture.controller(fixture.store(name)).restore(profile.kind, profile.id).closed);
    }

    @After public void restoreFixture() throws Exception {
        if (fixture == null) return;
        fixture.denied = false; fixture.recoverAll(false);
        if (before != null) assertEquals(before, fixture.observe(JournalFixture.TARGET).value);
    }
}
