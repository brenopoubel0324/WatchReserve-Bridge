package dev.brenopoubel.watchreserve.bridge.journal;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
import static dev.brenopoubel.watchreserve.bridge.journal.RestorationJournal.*;

public class RestorationJournalTest {
    private final RestorationJournal journal = new RestorationJournal("11111111-1111-4111-8111-111111111111",
        Kind.PROFILE, "a".repeat(64), List.of(new Entry("fixture.one", "uid:10120-v:1", null, "",
            State.PENDING, "Já está pronto.\nAção e conexão.")), false);

    @Test public void codecPreservesAbsenceEmptyAndUnicode() throws Exception {
        RestorationJournal decoded = decode(journal.encode());
        assertEquals(journal.id, decoded.id); assertEquals(journal.kind, decoded.kind);
        assertEquals(journal.environment, decoded.environment); assertFalse(decoded.closed);
        assertNull(decoded.entries.get(0).original); assertEquals("", decoded.entries.get(0).applied);
        assertEquals(journal.entries.get(0).reason, decoded.entries.get(0).reason);
    }

    @Test public void incompleteUnknownAndOversizedRecordsFailClosed() throws Exception {
        for (String bad : List.of("", "schema=2", "x".repeat(MAX_BYTES + 1), journal.encode() + "unknown=x\n",
                journal.encode().replace("closed=false", "closed=yes"), journal.encode().replace("size=1", "size=17"),
                journal.encode() + "closed=false\n",
                journal.encode().replace("PENDING", "ROOT"), journal.encode().replace("original.present=false", "original.present=true"))) {
            assertThrows(Exception.class, () -> decode(bad));
        }
    }

    @Test public void cannotInventClosedSessionOrDuplicateTargets() {
        assertThrows(IllegalArgumentException.class, () -> new RestorationJournal(journal.id, journal.kind,
            journal.environment, journal.entries, true));
        assertThrows(IllegalArgumentException.class, () -> new RestorationJournal(journal.id, journal.kind,
            journal.environment, List.of(journal.entries.get(0), journal.entries.get(0)), false));
    }

    @Test public void entryCollectionAndOriginalValuesAreImmutable() {
        assertThrows(UnsupportedOperationException.class, () -> journal.entries.clear());
        RestorationJournal changed = journal.replace(0, journal.entries.get(0).state(State.UNKNOWN, "Falha"));
        assertEquals(State.PENDING, journal.entries.get(0).state); assertEquals(State.UNKNOWN, changed.entries.get(0).state);
        assertEquals(journal.entries.get(0).original, changed.entries.get(0).original);
    }

    @Test public void unicodeCannotBypassByteLimitWithFewerCharacters() {
        StringBuilder text = new StringBuilder("schema=1\nid=" + journal.id + "\nkind=PROFILE\nenvironment="
            + journal.environment + "\nclosed=false\nsize=4\n");
        for (int i = 0; i < 4; i++) {
            String p = "entry." + i + ".";
            text.append(p).append("target=fixture.").append(i).append('\n')
                .append(p).append("identity=fixture\n").append(p).append("state=PENDING\n")
                .append(p).append("reason=\n").append(p).append("original.present=true\n")
                .append(p).append("original.value=").append("中".repeat(4096)).append('\n')
                .append(p).append("applied.present=true\n").append(p).append("applied.value=")
                .append("中".repeat(4096)).append('\n');
        }
        assertTrue(text.length() < MAX_BYTES);
        assertThrows(IllegalArgumentException.class, () -> decode(text.toString()));
    }
}
