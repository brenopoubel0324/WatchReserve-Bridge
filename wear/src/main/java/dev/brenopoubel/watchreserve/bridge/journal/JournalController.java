package dev.brenopoubel.watchreserve.bridge.journal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import static dev.brenopoubel.watchreserve.bridge.journal.RestorationJournal.*;

/** Protótipo sem backend de produção. Todas as entradas devem passar por um único dono. */
public final class JournalController {
    public interface Store {
        Object lock();
        RestorationJournal load(Kind kind) throws Exception;
        void save(RestorationJournal journal) throws Exception;
    }
    public interface Backend {
        /** Deve rejeitar comandos anteriores ainda em andamento antes da reconciliação. */
        void requireIdle() throws Exception;
        Observation observe(String target) throws Exception;
        void write(String target, String value) throws Exception;
    }
    public static final class Observation {
        public final String identity, value;
        public Observation(String identity, String value) { this.identity = identity; this.value = value; }
    }
    public static final class Change {
        public final String target, applied;
        public Change(String target, String applied) {
            require(target != null && target.matches("[a-zA-Z0-9._:-]{1,160}"), "Alvo inválido.");
            require(applied == null || applied.length() <= 4096, "Valor aplicado excessivo.");
            this.target = target; this.applied = applied;
        }
    }

    private final Store store;
    private final Backend backend;
    private final String environment;
    public JournalController(Store store, Backend backend, String environment) {
        this.store = store; this.backend = backend; this.environment = environment;
        require(environment != null && environment.matches("[a-f0-9]{64}"), "Vínculo de ambiente inválido.");
    }

    public RestorationJournal prepare(Kind kind, List<Change> changes) throws Exception {
        synchronized (store.lock()) {
            RestorationJournal previous = store.load(kind);
            if (previous != null && !previous.closed) {
                compatible(previous);
                require(changes.size() == previous.entries.size(), "Há uma sessão pendente.");
                for (int i = 0; i < changes.size(); i++) {
                    Change change = changes.get(i); Entry entry = previous.entries.get(i);
                    require(entry.target.equals(change.target) && Objects.equals(entry.applied, change.applied), "Há uma sessão pendente.");
                }
                return previous; // Nunca substitui o valor original de uma sessão aberta.
            }
            guard(kind, true); backend.requireIdle();
            require(!changes.isEmpty() && changes.size() <= 16, "Quantidade de ajustes inválida.");
            List<Entry> entries = new ArrayList<>();
            for (Change change : changes) {
                Observation before = backend.observe(change.target);
                entries.add(new Entry(change.target, before.identity, before.value, change.applied,
                    Objects.equals(before.value, change.applied) ? State.UNCHANGED : State.PENDING, ""));
            }
            RestorationJournal journal = new RestorationJournal(UUID.randomUUID().toString(), kind, environment, entries, false);
            store.save(journal); return journal;
        }
    }

    public RestorationJournal apply(Kind kind, String expectedId) throws Exception {
        synchronized (store.lock()) {
            RestorationJournal journal = current(kind, expectedId); guard(kind, true); backend.requireIdle();
            require(!journal.closed, "A sessão já foi encerrada.");
            for (Entry entry : journal.entries) require(!blocked(entry.state), "Reconcilie ou restaure a sessão antes de novos ajustes.");
            for (int i = 0; i < journal.entries.size(); i++) {
                Entry entry = journal.entries.get(i);
                if (entry.finished()) continue;
                Observation before = backend.observe(entry.target);
                if (!entry.identity.equals(before.identity) || !Objects.equals(before.value,
                        entry.state == State.VERIFIED ? entry.applied : entry.original)) {
                    journal = save(journal.replace(i, entry.state(entry.state == State.VERIFIED ? State.CONFLICT : State.NOT_APPLIED,
                        "O alvo ou seu estado mudou."))); break;
                }
                if (entry.state == State.VERIFIED) continue;
                journal = perform(journal, i, false);
                if (blocked(journal.entries.get(i).state)) break;
            }
            return journal;
        }
    }

    public RestorationJournal reconcile(Kind kind, String expectedId) throws Exception {
        synchronized (store.lock()) {
            RestorationJournal journal = current(kind, expectedId);
            if (journal.closed) return journal;
            guard(kind, false); backend.requireIdle();
            for (int i = 0; i < journal.entries.size(); i++) {
                Entry entry = journal.entries.get(i);
                if (entry.finished() || entry.state == State.PENDING || entry.state == State.NOT_APPLIED) continue;
                try {
                    Observation observed = backend.observe(entry.target);
                    State state = !entry.identity.equals(observed.identity) ? State.CONFLICT
                        : Objects.equals(observed.value, entry.original) ? State.RESTORED
                        : Objects.equals(observed.value, entry.applied) ? State.VERIFIED : State.CONFLICT;
                    journal = save(journal.replace(i, entry.state(state,
                        state == State.CONFLICT ? "Mudança externa preservada." : "Estado relido; nenhuma escrita repetida.")));
                } catch (Exception failure) {
                    journal = save(journal.replace(i, entry.state(State.UNKNOWN, failure.getClass().getSimpleName())));
                    break;
                }
            }
            return save(journal.closeIfRestored());
        }
    }

    public RestorationJournal restore(Kind kind, String expectedId) throws Exception {
        synchronized (store.lock()) {
            RestorationJournal journal = current(kind, expectedId);
            if (journal.closed) return journal;
            guard(kind, false); backend.requireIdle();
            for (int i = journal.entries.size() - 1; i >= 0; i--) {
                Entry entry = journal.entries.get(i);
                if (entry.finished()) continue;
                if (entry.state == State.PENDING || entry.state == State.NOT_APPLIED) {
                    journal = save(journal.replace(i, entry.state(State.UNCHANGED, "A escrita não foi iniciada."))); continue;
                }
                Observation observed;
                try { observed = backend.observe(entry.target); }
                catch (Exception failure) {
                    journal = save(journal.replace(i, entry.state(State.UNKNOWN, failure.getClass().getSimpleName()))); break;
                }
                if (!entry.identity.equals(observed.identity) || !Objects.equals(observed.value, entry.original) &&
                        !Objects.equals(observed.value, entry.applied)) {
                    journal = save(journal.replace(i, entry.state(State.CONFLICT, "Mudança externa preservada."))); continue;
                }
                if (Objects.equals(observed.value, entry.original)) {
                    journal = save(journal.replace(i, entry.state(State.RESTORED, "Original já observado."))); continue;
                }
                journal = perform(journal, i, true);
                if (journal.entries.get(i).state == State.UNKNOWN) break;
            }
            return save(journal.closeIfRestored());
        }
    }

    private RestorationJournal perform(RestorationJournal journal, int index, boolean restoring) throws Exception {
        Entry entry = journal.entries.get(index);
        journal = save(journal.replace(index, entry.state(restoring ? State.RESTORE_INTENDED : State.INTENDED, "")));
        boolean invoked = false;
        try {
            backend.requireIdle();
            Observation before = backend.observe(entry.target);
            String expected = restoring ? entry.applied : entry.original;
            if (!entry.identity.equals(before.identity) || !Objects.equals(expected, before.value)) {
                return save(journal.replace(index, entry.state(restoring ? State.CONFLICT : State.NOT_APPLIED,
                    "Mudança detectada após gravar a intenção; comando não iniciado.")));
            }
            String desired = restoring ? entry.original : entry.applied;
            invoked = true;
            backend.write(entry.target, desired);
            Observation after = backend.observe(entry.target);
            boolean confirmed = entry.identity.equals(after.identity) && Objects.equals(after.value, desired);
            return save(journal.replace(index, entry.state(confirmed ? (restoring ? State.RESTORED : State.VERIFIED)
                : State.UNKNOWN, confirmed ? "Resultado observado." : "Aplicação sem confirmação independente.")));
        } catch (Exception failure) {
            return save(journal.replace(index, entry.state(!invoked && !restoring ? State.NOT_APPLIED : State.UNKNOWN,
                failure.getClass().getSimpleName())));
        }
    }

    private void guard(Kind kind, boolean applying) throws Exception {
        if (kind != Kind.RECOVERY) require(!open(Kind.RECOVERY), "A recuperação temporária está pendente.");
        if (kind == Kind.PREPARATION) require(!open(Kind.PROFILE), "Restaure o perfil antes da preparação.");
        if (kind == Kind.PROFILE && applying) {
            RestorationJournal preparation = store.load(Kind.PREPARATION);
            if (preparation != null && !preparation.closed) {
                compatible(preparation);
                for (Entry entry : preparation.entries) require(entry.state == State.VERIFIED || entry.finished(),
                    "A preparação ainda não está confirmada.");
            }
        }
    }

    private boolean open(Kind kind) throws Exception { RestorationJournal journal = store.load(kind); return journal != null && !journal.closed; }
    private RestorationJournal current(Kind kind, String expectedId) throws Exception {
        RestorationJournal journal = store.load(kind); require(journal != null, "Diário ausente.");
        require(journal.id.equals(expectedId), "A resposta pertence a outra sessão."); compatible(journal); return journal;
    }
    private void compatible(RestorationJournal journal) { require(environment.equals(journal.environment), "Ambiente alterado; recuperação manual necessária."); }
    private RestorationJournal save(RestorationJournal journal) throws Exception { store.save(journal); return journal; }
    private static boolean blocked(State state) {
        return state == State.INTENDED || state == State.UNKNOWN || state == State.RESTORE_INTENDED ||
            state == State.CONFLICT || state == State.NOT_APPLIED;
    }
}
