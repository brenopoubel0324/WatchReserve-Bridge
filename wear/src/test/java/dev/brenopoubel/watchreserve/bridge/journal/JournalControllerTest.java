package dev.brenopoubel.watchreserve.bridge.journal;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static dev.brenopoubel.watchreserve.bridge.journal.RestorationJournal.*;
import static dev.brenopoubel.watchreserve.bridge.journal.JournalController.*;

public class JournalControllerTest {
    private static final String ENV = "a".repeat(64), TARGET = "fixture.one";
    private final MemoryStore store = new MemoryStore();
    private final Fixture backend = new Fixture();
    private final JournalController controller = new JournalController(store, backend, ENV);

    private RestorationJournal prepare(String applied) throws Exception {
        return controller.prepare(Kind.PROFILE, List.of(new Change(TARGET, applied)));
    }
    private RestorationJournal apply(RestorationJournal j) throws Exception { return controller.apply(j.kind, j.id); }
    private RestorationJournal restore(RestorationJournal j) throws Exception { return controller.restore(j.kind, j.id); }
    private RestorationJournal reconcile(RestorationJournal j) throws Exception { return controller.reconcile(j.kind, j.id); }

    @Test public void originalMissingEmptyLiteralNullDefaultAndEnabledRemainDistinct() throws Exception {
        for (String original : Arrays.asList(null, "", "null", "0", "1")) {
            backend.values.put(TARGET, original);
            RestorationJournal journal = prepare("3");
            assertEquals(original, journal.entries.get(0).original);
            apply(journal); assertEquals("3", backend.values.get(TARGET));
            assertTrue(restore(journal).closed); assertEquals(original, backend.values.get(TARGET));
        }
    }

    @Test public void reappliedSessionKeepsOriginalIdentityAndDoesNotWriteTwice() throws Exception {
        backend.values.put(TARGET, "0"); RestorationJournal first = prepare("3"); apply(first);
        RestorationJournal second = prepare("3"); apply(second);
        assertEquals(first.id, second.id); assertEquals("0", second.entries.get(0).original);
        assertEquals(1, backend.writes.size());
        assertThrows(IllegalArgumentException.class, () -> prepare("1"));
    }

    @Test public void externalConflictIsPreservedUntilCompatibleAgain() throws Exception {
        backend.values.put(TARGET, "0"); RestorationJournal journal = prepare("3"); apply(journal);
        backend.values.put(TARGET, "external");
        assertEquals(State.CONFLICT, restore(journal).entries.get(0).state);
        assertEquals(State.CONFLICT, reconcile(journal).entries.get(0).state);
        assertEquals("external", backend.values.get(TARGET)); assertEquals(1, backend.writes.size());
        backend.values.put(TARGET, "3"); assertTrue(restore(journal).closed); assertEquals("0", backend.values.get(TARGET));
    }

    @Test public void replacedTargetIsNeverRestoredEvenWithSameValue() throws Exception {
        backend.values.put(TARGET, "0"); RestorationJournal journal = prepare("3"); apply(journal);
        backend.identity = "different-package-version";
        assertEquals(State.CONFLICT, restore(journal).entries.get(0).state);
        assertEquals("3", backend.values.get(TARGET)); assertEquals(1, backend.writes.size());
    }

    @Test public void changedEnvironmentRejectsApplyReadReconciliationAndRestore() throws Exception {
        RestorationJournal journal = prepare("3");
        JournalController changed = new JournalController(store, backend, "b".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> changed.apply(journal.kind, journal.id));
        assertThrows(IllegalArgumentException.class, () -> changed.reconcile(journal.kind, journal.id));
        assertThrows(IllegalArgumentException.class, () -> changed.restore(journal.kind, journal.id));
        assertTrue(backend.writes.isEmpty()); assertFalse(store.load(journal.kind).closed);
    }

    @Test public void staleCallbackCannotApplyOrRestoreNextSession() throws Exception {
        RestorationJournal old = prepare("3"); restore(old);
        RestorationJournal next = prepare("1"); assertNotEquals(old.id, next.id);
        assertThrows(IllegalArgumentException.class, () -> apply(old));
        assertThrows(IllegalArgumentException.class, () -> restore(old));
        assertThrows(IllegalArgumentException.class, () -> reconcile(old));
        assertEquals(State.PENDING, store.load(next.kind).entries.get(0).state); assertTrue(backend.writes.isEmpty());
    }

    @Test public void failureBeforeWriteLeavesUnknownAndRequiresExplicitReconciliation() throws Exception {
        backend.values.put(TARGET, "0"); RestorationJournal journal = prepare("3"); backend.failBefore = true;
        assertEquals(State.UNKNOWN, apply(journal).entries.get(0).state);
        assertEquals("0", backend.values.get(TARGET)); assertThrows(IllegalArgumentException.class, () -> apply(journal));
        assertTrue(reconcile(journal).closed); assertEquals(1, backend.writes.size());
    }

    @Test public void lostResponseAfterWriteIsObservedWithoutRepeatingCommand() throws Exception {
        backend.values.put(TARGET, "0"); RestorationJournal journal = prepare("3"); backend.failAfter = true;
        assertEquals(State.UNKNOWN, apply(journal).entries.get(0).state); backend.failAfter = false;
        assertEquals(State.VERIFIED, reconcile(journal).entries.get(0).state);
        assertEquals(1, backend.writes.size()); assertTrue(restore(journal).closed); assertEquals("0", backend.values.get(TARGET));
    }

    @Test public void deathAfterWriteKeepsDurableIntentionAcrossControllerRecreation() throws Exception {
        backend.values.put(TARGET, "0"); RestorationJournal journal = prepare("3"); backend.dieAfter = true;
        assertThrows(SimulatedDeath.class, () -> apply(journal));
        assertEquals(State.INTENDED, store.load(journal.kind).entries.get(0).state);
        backend.dieAfter = false;
        JournalController reopened = new JournalController(store, backend, ENV);
        assertEquals(State.VERIFIED, reopened.reconcile(journal.kind, journal.id).entries.get(0).state);
        assertEquals(1, backend.writes.size()); assertTrue(reopened.restore(journal.kind, journal.id).closed);
    }

    @Test public void failedWriteAheadPersistencePreventsCommand() throws Exception {
        RestorationJournal journal = prepare("3"); store.failState = State.INTENDED;
        assertThrows(IOException.class, () -> apply(journal));
        assertTrue(backend.writes.isEmpty()); assertEquals(State.PENDING, store.load(journal.kind).entries.get(0).state);
    }

    @Test public void failedConfirmationPersistenceKeepsRecoveryPending() throws Exception {
        RestorationJournal journal = prepare("3"); store.failState = State.VERIFIED;
        assertEquals(State.UNKNOWN, apply(journal).entries.get(0).state);
        assertEquals("3", backend.values.get(TARGET)); store.failState = null;
        assertTrue(restore(journal).closed); assertNull(backend.values.get(TARGET));
    }

    @Test public void unknownFirstWriteDoesNotApplyNextTarget() throws Exception {
        RestorationJournal journal = controller.prepare(Kind.PROFILE,
            List.of(new Change(TARGET, "3"), new Change("fixture.two", "3")));
        backend.failAfter = true; apply(journal);
        assertEquals(1, backend.writes.size()); assertNull(backend.values.get("fixture.two"));
        assertEquals(State.PENDING, store.load(journal.kind).entries.get(1).state);
    }

    @Test public void restoreRunsInReverseOrderAndDoesNotRepeatRestoredWrites() throws Exception {
        RestorationJournal journal = controller.prepare(Kind.PROFILE,
            List.of(new Change(TARGET, "3"), new Change("fixture.two", "1")));
        apply(journal); restore(journal); restore(journal);
        assertEquals(List.of(TARGET, "fixture.two", "fixture.two", TARGET), backend.writes);
    }

    @Test public void untouchedOrNeverStartedEntryPreservesLaterExternalChange() throws Exception {
        backend.values.put(TARGET, "3"); RestorationJournal unchanged = prepare("3");
        backend.values.put(TARGET, "external"); assertTrue(restore(unchanged).closed);
        assertEquals("external", backend.values.get(TARGET)); assertTrue(backend.writes.isEmpty());
        RestorationJournal pending = prepare("0"); backend.values.put(TARGET, "other");
        assertTrue(restore(pending).closed); assertEquals("other", backend.values.get(TARGET)); assertTrue(backend.writes.isEmpty());
    }

    @Test public void threeResponsibilitiesRestoreNestedOriginalsAndBlockWrongOrder() throws Exception {
        backend.values.put(TARGET, "0");
        RestorationJournal preparation = controller.prepare(Kind.PREPARATION, List.of(new Change(TARGET, "1"))); apply(preparation);
        RestorationJournal profile = prepare("3"); apply(profile);
        assertThrows(IllegalArgumentException.class, () -> restore(preparation));
        RestorationJournal recovery = controller.prepare(Kind.RECOVERY, List.of(new Change(TARGET, "1"))); apply(recovery);
        assertThrows(IllegalArgumentException.class, () -> reconcile(profile));
        assertThrows(IllegalArgumentException.class, () -> reconcile(preparation));
        assertThrows(IllegalArgumentException.class, () -> restore(profile));
        assertThrows(IllegalArgumentException.class, () -> apply(profile));
        assertTrue(restore(recovery).closed); assertEquals("3", backend.values.get(TARGET));
        assertTrue(restore(profile).closed); assertEquals("1", backend.values.get(TARGET));
        assertTrue(restore(preparation).closed); assertEquals("0", backend.values.get(TARGET));
        assertEquals(3, store.data.size());
    }

    @Test public void pendingPreparationBlocksNewProfile() throws Exception {
        controller.prepare(Kind.PREPARATION, List.of(new Change(TARGET, "1")));
        assertThrows(IllegalArgumentException.class, () -> prepare("3")); assertTrue(backend.writes.isEmpty());
    }

    @Test public void profileRestorationNeedsAccessButNotNewPreparationApproval() throws Exception {
        backend.values.put(TARGET, "0");
        RestorationJournal preparation = controller.prepare(Kind.PREPARATION, List.of(new Change("fixture.preparation", "1")));
        apply(preparation); RestorationJournal profile = prepare("3"); apply(profile);
        store.save(preparation.replace(0, preparation.entries.get(0).state(State.UNKNOWN, "Leitura indisponível.")));
        assertTrue(restore(profile).closed); assertEquals("0", backend.values.get(TARGET));
        assertEquals(State.UNKNOWN, store.load(Kind.PREPARATION).entries.get(0).state);
    }

    @Test public void earlierCommandStillRunningBlocksAllMutationsAndReconciliation() throws Exception {
        RestorationJournal journal = prepare("3"); backend.busy = true;
        assertThrows(IllegalStateException.class, () -> apply(journal));
        assertThrows(IllegalStateException.class, () -> restore(journal));
        assertThrows(IllegalStateException.class, () -> reconcile(journal)); assertTrue(backend.writes.isEmpty());
    }

    @Test public void malformedJournalBlocksRatherThanReplacingOriginals() throws Exception {
        store.data.put(Kind.PROFILE, "schema=999");
        assertThrows(IllegalArgumentException.class, () -> prepare("3")); assertTrue(backend.writes.isEmpty());
        assertEquals("schema=999", store.data.get(Kind.PROFILE));
    }

    @Test public void concurrentControllersShareCaptureAndOnlyOneWrite() throws Exception {
        backend.values.put(TARGET, "0"); CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) results.add(executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                JournalController other = new JournalController(store, backend, ENV);
                RestorationJournal journal = other.prepare(Kind.PROFILE, List.of(new Change(TARGET, "3")));
                other.apply(journal.kind, journal.id); return journal.id;
            }));
            start.countDown(); assertEquals(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS));
            assertEquals(1, backend.writes.size()); assertEquals("0", store.load(Kind.PROFILE).entries.get(0).original);
        } finally { executor.shutdownNow(); }
    }

    @Test public void externalValueBeforeApplyIsNotOwnedEvenIfMatchingRequestedValue() throws Exception {
        backend.values.put(TARGET, "0"); RestorationJournal journal = prepare("3");
        backend.values.put(TARGET, "3"); RestorationJournal attempted = apply(journal);
        reconcile(journal); assertTrue(restore(journal).closed);
        assertEquals("3", backend.values.get(TARGET)); assertTrue(backend.writes.isEmpty());
        assertEquals(State.NOT_APPLIED, attempted.entries.get(0).state);
    }

    @Test public void valueChangedDuringIntentPersistencePreventsCommand() throws Exception {
        backend.values.put(TARGET, "0"); RestorationJournal journal = prepare("3");
        store.onIntent = () -> backend.values.put(TARGET, "external");
        RestorationJournal attempted = apply(journal);
        assertTrue(backend.writes.isEmpty()); assertEquals(State.NOT_APPLIED, attempted.entries.get(0).state);
        assertTrue(restore(journal).closed); assertEquals("external", backend.values.get(TARGET));
    }

    @Test public void identityChangedDuringIntentPersistencePreventsCommand() throws Exception {
        RestorationJournal journal = prepare("3"); store.onIntent = () -> backend.identity = "replacement";
        RestorationJournal attempted = apply(journal);
        assertTrue(backend.writes.isEmpty()); assertEquals(State.NOT_APPLIED, attempted.entries.get(0).state);
        assertTrue(restore(journal).closed); assertNull(backend.values.get(TARGET));
    }

    @Test public void externalChangeDuringRestoreIntentIsPreserved() throws Exception {
        backend.values.put(TARGET, "0"); RestorationJournal journal = prepare("3"); apply(journal);
        store.onRestoreIntent = () -> backend.values.put(TARGET, "external");
        RestorationJournal restored = restore(journal);
        assertEquals("external", backend.values.get(TARGET)); assertEquals(1, backend.writes.size());
        assertFalse(restored.closed); assertEquals(State.CONFLICT, restored.entries.get(0).state);
    }

    @Test public void blockedBackendBeforeInvocationNeverAssumesOwnership() throws Exception {
        RestorationJournal journal = prepare("3"); store.onIntent = () -> backend.busy = true;
        RestorationJournal attempted = apply(journal); backend.busy = false;
        assertTrue(backend.writes.isEmpty()); assertEquals(State.NOT_APPLIED, attempted.entries.get(0).state);
        assertTrue(restore(journal).closed);
    }

    private static final class SimulatedDeath extends Error { }
    private static final class Fixture implements Backend {
        final Map<String, String> values = new HashMap<>(); final List<String> writes = new ArrayList<>();
        String identity = "fixture-version-one"; boolean failBefore, failAfter, dieAfter, busy;
        @Override public void requireIdle() { if (busy) throw new IllegalStateException("Comando anterior pendente."); }
        @Override public Observation observe(String target) { return new Observation(identity, values.get(target)); }
        @Override public void write(String target, String value) throws Exception {
            writes.add(target); if (failBefore) throw new IOException("Antes da escrita."); values.put(target, value);
            if (dieAfter) throw new SimulatedDeath(); if (failAfter) throw new IOException("Depois da escrita.");
        }
    }
    private static final class MemoryStore implements Store {
        final Map<Kind, String> data = new EnumMap<>(Kind.class); State failState;
        Runnable onIntent, onRestoreIntent;
        @Override public Object lock() { return this; }
        @Override public RestorationJournal load(Kind kind) throws Exception {
            return data.containsKey(kind) ? decode(data.get(kind)) : null;
        }
        @Override public void save(RestorationJournal journal) throws Exception {
            if (failState != null) for (Entry entry : journal.entries) if (entry.state == failState) throw new IOException("Disco indisponível.");
            data.put(journal.kind, journal.encode());
            for (Entry entry : journal.entries) {
                if (entry.state == State.INTENDED && onIntent != null) { Runnable action = onIntent; onIntent = null; action.run(); }
                if (entry.state == State.RESTORE_INTENDED && onRestoreIntent != null) {
                    Runnable action = onRestoreIntent; onRestoreIntent = null; action.run();
                }
            }
        }
    }
}
