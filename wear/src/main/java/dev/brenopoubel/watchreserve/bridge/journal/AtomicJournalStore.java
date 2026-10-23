package dev.brenopoubel.watchreserve.bridge.journal;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static dev.brenopoubel.watchreserve.bridge.journal.RestorationJournal.*;

/** Um arquivo por responsabilidade, privado e excluído de backup. Sem descarte automático. */
public final class AtomicJournalStore implements JournalController.Store {
    // O futuro IPC deve permanecer no processo do gerenciador; AtomicFile não é um lock entre processos.
    private static final Object LOCK = new Object();
    private final File directory;
    public AtomicJournalStore(Context context) {
        directory = new File(context.getNoBackupFilesDir(), "restoration");
        require(directory.isDirectory() || directory.mkdirs(), "Armazenamento de restauração indisponível.");
    }
    @Override public Object lock() { return LOCK; }
    private AtomicFile file(Kind kind) { return new AtomicFile(new File(directory, kind.name().toLowerCase(java.util.Locale.ROOT) + ".properties")); }

    @Override public RestorationJournal load(Kind kind) throws Exception {
        synchronized (LOCK) {
            AtomicFile file = file(kind);
            if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return null;
            byte[] bytes = readBytes(file);
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            RestorationJournal journal = decode(text);
            require(journal.kind == kind, "Tipo de diário divergente."); return journal;
        }
    }

    @Override public void save(RestorationJournal journal) throws Exception {
        synchronized (LOCK) {
            byte[] bytes = journal.encode().getBytes(StandardCharsets.UTF_8);
            AtomicFile file = file(journal.kind); FileOutputStream stream = file.startWrite();
            boolean finished = false;
            try {
                stream.write(bytes);
                // finishWrite registra algumas falhas sem lançá-las; a escrita real exige confirmação.
                stream.getFD().sync();
                file.finishWrite(stream); finished = true;
                if (!Arrays.equals(bytes, readBytes(file))) throw new IOException("O diário gravado diverge da intenção.");
            } catch (Exception failure) {
                if (!finished) file.failWrite(stream);
                throw failure;
            }
        }
    }

    private static byte[] readBytes(AtomicFile file) throws IOException {
        byte[] buffer = new byte[MAX_BYTES + 1]; int length = 0;
        try (InputStream input = file.openRead()) {
            while (length < buffer.length) {
                int count = input.read(buffer, length, buffer.length - length);
                if (count < 0) break;
                length += count;
            }
        }
        if (length > MAX_BYTES) throw new IOException("Registro de restauração excessivo.");
        return Arrays.copyOf(buffer, length);
    }
}
