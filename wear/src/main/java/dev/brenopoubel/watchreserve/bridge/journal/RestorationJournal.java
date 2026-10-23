package dev.brenopoubel.watchreserve.bridge.journal;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Registro de alterações; não executa comandos nem concede capacidades. */
public final class RestorationJournal {
    public static final int MAX_BYTES = 65_536;
    public enum Kind { PREPARATION, RECOVERY, PROFILE }
    public enum State { PENDING, INTENDED, VERIFIED, UNKNOWN, RESTORE_INTENDED, CONFLICT, RESTORED, UNCHANGED, NOT_APPLIED }

    public static final class Entry {
        public final String target, identity, original, applied, reason;
        public final State state;

        public Entry(String target, String identity, String original, String applied, State state, String reason) {
            require(target != null && target.matches("[a-zA-Z0-9._:-]{1,160}"), "Alvo inválido.");
            require(identity != null && !identity.isEmpty() && identity.length() <= 512, "Identidade ausente.");
            require(original == null || original.length() <= 4096, "Valor original excessivo.");
            require(applied == null || applied.length() <= 4096, "Valor aplicado excessivo.");
            require(reason != null && reason.length() <= 512, "Descrição excessiva.");
            this.target = target; this.identity = identity; this.original = original; this.applied = applied;
            this.state = Objects.requireNonNull(state); this.reason = reason;
        }

        public Entry state(State next, String detail) { return new Entry(target, identity, original, applied, next, detail); }
        public boolean finished() { return state == State.RESTORED || state == State.UNCHANGED; }
    }

    public final String id, environment;
    public final Kind kind;
    public final List<Entry> entries;
    public final boolean closed;

    public RestorationJournal(String id, Kind kind, String environment, List<Entry> entries, boolean closed) {
        require(id != null && UUID.fromString(id).toString().equals(id), "Sessão inválida.");
        require(environment != null && environment.matches("[a-f0-9]{64}"), "Vínculo de ambiente inválido.");
        require(entries != null && !entries.isEmpty() && entries.size() <= 16, "Quantidade de ajustes inválida.");
        Set<String> targets = new HashSet<>();
        for (Entry entry : entries) {
            require(entry != null && targets.add(entry.target), "Alvo repetido.");
            require(!closed || entry.finished(), "Sessão concluída com restauração pendente.");
        }
        this.id = id; this.kind = Objects.requireNonNull(kind); this.environment = environment;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries)); this.closed = closed;
    }

    public RestorationJournal replace(int index, Entry entry) {
        List<Entry> next = new ArrayList<>(entries); next.set(index, entry);
        return new RestorationJournal(id, kind, environment, next, false);
    }

    public RestorationJournal closeIfRestored() {
        boolean finished = true;
        for (Entry entry : entries) finished &= entry.finished();
        return new RestorationJournal(id, kind, environment, entries, finished);
    }

    public String encode() throws java.io.IOException {
        Properties p = new Properties();
        p.setProperty("schema", "1"); p.setProperty("id", id); p.setProperty("kind", kind.name());
        p.setProperty("environment", environment); p.setProperty("closed", Boolean.toString(closed));
        p.setProperty("size", Integer.toString(entries.size()));
        for (int i = 0; i < entries.size(); i++) {
            String prefix = "entry." + i + "."; Entry e = entries.get(i);
            p.setProperty(prefix + "target", e.target); p.setProperty(prefix + "identity", e.identity);
            p.setProperty(prefix + "state", e.state.name()); p.setProperty(prefix + "reason", e.reason);
            nullable(p, prefix + "original", e.original); nullable(p, prefix + "applied", e.applied);
        }
        StringWriter writer = new StringWriter(); p.store(writer, null);
        String encoded = writer.toString();
        require(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_BYTES, "Registro excessivo.");
        return encoded;
    }

    private static void nullable(Properties p, String key, String value) {
        p.setProperty(key + ".present", Boolean.toString(value != null));
        if (value != null) p.setProperty(key + ".value", value);
    }

    public static RestorationJournal decode(String text) throws java.io.IOException {
        require(text.length() <= MAX_BYTES, "Registro excessivo.");
        require(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_BYTES, "Registro excessivo.");
        Properties p = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                require(!containsKey(key), "Campo de diário duplicado.");
                return super.put(key, value);
            }
        };
        p.load(new StringReader(text));
        Set<String> remaining = new HashSet<>(p.stringPropertyNames());
        Parser parser = new Parser(p, remaining);
        require(parser.field("schema").equals("1"), "Formato de diário não reconhecido.");
        String id = parser.field("id"); Kind kind = Kind.valueOf(parser.field("kind"));
        String environment = parser.field("environment"); boolean closed = parser.bool("closed");
        int size = Integer.parseInt(parser.field("size"));
        require(size >= 1 && size <= 16, "Quantidade de ajustes inválida.");
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            String prefix = "entry." + i + ".";
            entries.add(new Entry(parser.field(prefix + "target"), parser.field(prefix + "identity"),
                parser.nullable(prefix + "original"), parser.nullable(prefix + "applied"),
                State.valueOf(parser.field(prefix + "state")), parser.field(prefix + "reason")));
        }
        require(remaining.isEmpty(), "Campos de diário não reconhecidos.");
        return new RestorationJournal(id, kind, environment, entries, closed);
    }

    private static final class Parser {
        private final Properties p; private final Set<String> remaining;
        Parser(Properties p, Set<String> remaining) { this.p = p; this.remaining = remaining; }
        String field(String key) {
            String value = p.getProperty(key); require(value != null, "Campo ausente: " + key);
            remaining.remove(key); return value;
        }
        boolean bool(String key) {
            String value = field(key); require(value.equals("true") || value.equals("false"), "Booleano inválido.");
            return value.equals("true");
        }
        String nullable(String key) { return bool(key + ".present") ? field(key + ".value") : null; }
    }

    static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
