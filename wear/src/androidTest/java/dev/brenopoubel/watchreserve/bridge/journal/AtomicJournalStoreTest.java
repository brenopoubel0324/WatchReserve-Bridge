package dev.brenopoubel.watchreserve.bridge.journal;

import android.content.ContextWrapper;
import android.util.AtomicFile;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;
import static dev.brenopoubel.watchreserve.bridge.journal.RestorationJournal.*;

/** Falhas de arquivo em fixture sem backend de escrita ou alteração de pacote. */
@RunWith(AndroidJUnit4.class)
public final class AtomicJournalStoreTest {
    private File directory;
    private AtomicJournalStore store;
    private RestorationJournal journal;
    @Before public void prepareStorage() throws Exception {
        JournalFixture fixture = new JournalFixture();
        directory = new File(fixture.context.getNoBackupFilesDir(), "journal-storage-fixture-" + UUID.randomUUID());
        assertTrue(directory.mkdir());
        store = new AtomicJournalStore(new ContextWrapper(fixture.context) {
            @Override public File getNoBackupFilesDir() { return directory; }
        });
        journal = new RestorationJournal(UUID.randomUUID().toString(), Kind.PROFILE, fixture.environment(),
            List.of(new Entry("fixture.one", "fixture-id", null, "0", State.PENDING, "")), false);
    }

    @Test public void atomicBackupSurvivesTornReplacement() throws Exception {
        store.save(journal); File file = new File(directory, "restoration/profile.properties");
        assertTrue(file.renameTo(new File(file + ".bak")));
        try (FileOutputStream output = new FileOutputStream(file)) { output.write("torn".getBytes(StandardCharsets.UTF_8)); }
        assertEquals(journal.id, store.load(Kind.PROFILE).id); assertNull(store.load(Kind.PROFILE).entries.get(0).original);
        assertEquals("0", store.load(Kind.PROFILE).entries.get(0).applied);
    }

    @Test public void malformedUtf8AndWrongKindRemainUnreadableWithoutDeletingData() throws Exception {
        File file = new File(directory, "restoration/profile.properties");
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(new byte[]{(byte)0xc3, 0x28}); }
        assertThrows(java.nio.charset.CharacterCodingException.class, () -> store.load(Kind.PROFILE));
        assertEquals(2, file.length());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(journal.encode().replace("kind=PROFILE", "kind=RECOVERY").getBytes(StandardCharsets.UTF_8));
        }
        assertThrows(IllegalArgumentException.class, () -> store.load(Kind.PROFILE)); assertTrue(file.exists());
    }

    @Test public void failedRenameCannotBeReportedAsSuccessfulCommit() throws Exception {
        File target = new File(directory, "restoration/profile.properties");
        assertTrue(target.mkdir());
        File blocker = new File(target, "keep-fixture"); assertTrue(blocker.createNewFile());
        assertThrows(java.io.IOException.class, () -> store.save(journal));
        assertTrue(target.isDirectory()); assertTrue(blocker.exists());
    }

    @After public void removeStorageFixtureOnly() {
        if (directory == null) return;
        File storage = new File(directory, "restoration");
        File blocker = new File(storage, "profile.properties/keep-fixture");
        if (blocker.exists()) { assertTrue(blocker.delete()); assertTrue(blocker.getParentFile().delete()); }
        for (Kind kind : Kind.values()) new AtomicFile(new File(storage,
            kind.name().toLowerCase(java.util.Locale.ROOT) + ".properties")).delete();
        assertTrue(storage.delete()); assertTrue(directory.delete());
    }
}
