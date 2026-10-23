package dev.brenopoubel.watchreserve.bridge.journal;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static dev.brenopoubel.watchreserve.bridge.journal.RestorationJournal.*;

/** Métodos separados pelo roteiro: o primeiro deve encerrar a própria instrumentação. */
@RunWith(AndroidJUnit4.class)
public final class JournalCrashProbe {
    @Test public void interruptAfterFixtureWrite() throws Exception {
        JournalFixture fixture = new JournalFixture(); fixture.assertNoPending();
        String original = fixture.observe(JournalFixture.TARGET).value;
        assertTrue(original.equals("0") || original.equals("1"));
        AtomicJournalStore store = fixture.store("crash"); JournalController controller = fixture.controller(store);
        RestorationJournal journal = controller.prepare(Kind.PROFILE, fixture.change("3"));
        fixture.dieAfterWrite = true; controller.apply(journal.kind, journal.id);
        fail("A instrumentação deveria ter terminado antes de confirmar a escrita.");
    }

    @Test public void recoverPendingFixtures() throws Exception {
        JournalFixture fixture = new JournalFixture();
        fixture.recoverAll("true".equals(InstrumentationRegistry.getArguments().getString("requireCrashEvidence")));
        fixture.assertNoPending();
        assertTrue(fixture.pm.getApplicationInfo(JournalFixture.PACKAGE, 0).enabled);
    }
}
