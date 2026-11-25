package ponto.app;

import ponto.Bad;
import ponto.infra.Assist;
import ponto.infra.CompaniesStore;
import ponto.infra.Db;
import ponto.infra.Files;
import ponto.infra.KanbanStore;
import ponto.infra.NotesStore;
import ponto.infra.HttpClientStore;
import ponto.infra.Repo;
import ponto.infra.VaultStore;
import ponto.model.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Application facade shared by the terminal menu and the web API.
 * Controllers talk only to this type — never to infra stores.
 */
public final class Service {

    public record Warn(boolean sameTime, boolean midnight, String midnightLabel, Entry overlap) {
        public static Warn empty() { return new Warn(false, false, "", null); }
        public static Warn same() { return new Warn(true, false, "", null); }
    }

    public record Totals(long mins, int open) {
        public String dur() { return Dur.hm(mins); }
        public String hours() { return Dur.dec(mins); }
    }

    public record FilteredRange(List<Entry> entries, Totals totals, long total, long overtime) {}

    public record Bank(YearMonth ym, long saldoMins, String saldoLabel, int dailyMins,
                       int remaining, int planMins, String planLabel, boolean neg) { }

    public record BankDay(LocalDate dia, long mins, boolean falta, boolean normal, String kind) { }

    public record MonthSaldo(YearMonth ym, long saldoMins) { }

    public static final int DEFAULT_DAILY_MINS = 8 * 60;
    public static final int TOLERANCE_MINS = BankRules.TOLERANCE_MINS;

    private final Db db;
    private final Object lock;
    private final ReadCache cache;
    private final Map<Integer, Service> scopedServices;
    private final Holidays calendar;
    private final CompanyApp companies;
    private final NotesApp notes;
    private final KanbanApp kanban;
    private final FilesApp files;
    private final TimesheetApp timesheet;
    private final DiarioApp diario;
    private final VaultApp vault;
    private final HttpClientStore httpClient;

    private String cachedOffRaw;
    private Set<LocalDate> cachedOffSet = Set.of();

    public Service(Db db) {
        this(db, new CompaniesStore.Cache(), new ReadCache(), new HashMap<>());
    }

    private Service(Db db, CompaniesStore.Cache companyCache, ReadCache cache, Map<Integer, Service> scopedServices) {
        this.db = db;
        this.lock = db.lock;
        this.cache = cache;
        this.scopedServices = scopedServices;
        this.companies = new CompanyApp(new CompaniesStore(db, lock, companyCache));
        this.calendar = new Holidays(() -> offDates(companies.active().off()), this::extraOffDays);
        this.notes = new NotesApp(new NotesStore(db, lock));
        this.kanban = new KanbanApp(new KanbanStore(db, lock));
        this.files = new FilesApp(db, lock);
        this.timesheet = new TimesheetApp(db, lock, calendar, companies::active, this::who);
        this.diario = new DiarioApp(db, lock, timesheet.repo(), calendar,
                timesheet::dailyMinutes, timesheet::falta, timesheet::normal);
        this.vault = new VaultApp(new VaultStore(db, lock));
        this.httpClient = new HttpClientStore(db, lock);
    }

    /** Cached parsed extra days off for the active company (0 SQL + 0 parse on steady state). */
    public Set<LocalDate> extraOffDays() {
        synchronized (lock) {
            String raw = companies.active().off();
            if (raw == null) raw = "";
            if (raw.equals(cachedOffRaw)) return cachedOffSet;
            List<LocalDate> parsed = offDates(raw);
            cachedOffSet = parsed.isEmpty() ? Set.of() : Set.copyOf(parsed);
            cachedOffRaw = raw;
            return cachedOffSet;
        }
    }

    /** Scoped view for one company; cached per companyId and shares Files/Cache. */
    public Service forCompany(int id) {
        synchronized (lock) {
            companies.byId(id);
            Service hit = scopedServices.get(id);
            if (hit != null) return hit;
            Service scoped = new Service(db.forCompany(id), companies.store().cache(), cache, scopedServices);
            scoped.files.adopt(this.files);
            scopedServices.put(id, scoped);
            return scoped;
        }
    }

    public <T> T transaction(java.util.function.Supplier<T> work) { return db.transaction(work); }
    public boolean healthy() { return db.healthy(); }
    public LocalDate today() { return Time.today(); }
    public int companyId() { synchronized (lock) { return db.company(); } }
    public java.nio.file.Path dataDir() { return db.dataDir; }
    public Holidays calendar() { return calendar; }

    public static String maskDbUrl(String url) {
        if (url == null || url.isBlank() || !url.startsWith("jdbc:")) return url;
        String core = url;
        String params = "";
        int q = url.indexOf('?');
        if (q >= 0) { core = url.substring(0, q); params = url.substring(q); }
        int at = core.lastIndexOf('@');
        if (at >= 0) core = "jdbc:postgresql://***@" + core.substring(at + 1);
        StringBuilder masked = new StringBuilder(core);
        if (!params.isEmpty()) {
            StringBuilder p = new StringBuilder();
            for (String pair : params.substring(1).split("&")) {
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String val = eq < 0 ? "" : pair.substring(eq + 1);
                p.append(p.isEmpty() ? '?' : '&').append(key).append('=').append(key.equalsIgnoreCase("password") ? "***" : val);
            }
            masked.append(p);
        }
        return masked.toString();
    }

    public void deleteNote(int id) {
        db.transaction(() -> {
            notes.byId(id);
            files.scheduleNoteDeletion(id);
            notes.delete(id);
            return null;
        });
        cache.invalidate(companyId(), ReadCache.Domain.NOTES, ReadCache.Domain.DIARIO);
        files.kick();
    }

    public void deleteKanbanCard(int id) {
        db.transaction(() -> {
            kanban.byId(id);
            files.scheduleCardDeletion(id);
            kanban.deleteCard(id);
            return null;
        });
        cache.invalidate(companyId(), ReadCache.Domain.KANBAN, ReadCache.Domain.DIARIO);
        files.kick();
    }

    public void deleteKanbanColumn(int id) {
        db.transaction(() -> {
            for (KanbanCard card : kanban.cards()) {
                if (card.columnId() == id) files.scheduleCardDeletion(card.id());
            }
            kanban.deleteColumn(id);
            return null;
        });
        cache.invalidate(companyId(), ReadCache.Domain.KANBAN, ReadCache.Domain.DIARIO);
        files.kick();
    }

    public void closeResources() { files.close(); }
    public void startFiles() { files.start(); }
    public boolean storageUp() { return files.storageUp(); }
    public void retryStorage() { files.retry(); }
    public boolean storageConfigured() { return files.configured(); }
    public int pendingObjects() { return files.pending(); }
    public static long maxUpload() { return FilesApp.maxUpload(); }

    // ---- notes -----------------------------------------------------------------
    public List<Note> listNotes(String q) {
        if (q == null || q.isBlank()) {
            return cache.get(companyId(), ReadCache.Domain.NOTES, "list", () -> List.copyOf(notes.list(null)));
        }
        return notes.list(q);
    }
    public Note noteById(int id) { return notes.byId(id); }
    public Note createNote(String title, String body) {
        Note n = notes.create(title, body);
        cache.invalidate(companyId(), ReadCache.Domain.NOTES, ReadCache.Domain.DIARIO);
        return n;
    }
    public Note createNote(String title, String body, Integer folderId, List<Integer> tagIds) {
        Note n = notes.create(title, body, folderId, tagIds);
        cache.invalidate(companyId(), ReadCache.Domain.NOTES, ReadCache.Domain.DIARIO);
        return n;
    }
    public Note updateNote(int id, String title, String body, int revision) {
        Note n = notes.update(id, title, body, revision);
        cache.invalidate(companyId(), ReadCache.Domain.NOTES, ReadCache.Domain.DIARIO);
        return n;
    }
    public Note moveNote(int id, Integer folderId) {
        Note n = notes.move(id, folderId);
        cache.invalidate(companyId(), ReadCache.Domain.NOTES);
        return n;
    }
    public Note setNoteTags(int id, List<Integer> tagIds) {
        Note n = notes.setTags(id, tagIds);
        cache.invalidate(companyId(), ReadCache.Domain.NOTES);
        return n;
    }
    public Note addNoteTag(int id, int tagId) {
        Note n = notes.addTag(id, tagId);
        cache.invalidate(companyId(), ReadCache.Domain.NOTES);
        return n;
    }
    public List<Tag> tags() {
        return cache.get(companyId(), ReadCache.Domain.NOTES, "tags", () -> List.copyOf(notes.tags()));
    }
    public Tag createTag(String name, String color) {
        Tag t = notes.createTag(name, color);
        cache.invalidate(companyId(), ReadCache.Domain.NOTES);
        return t;
    }
    public Tag renameTag(int id, String name, String color) {
        Tag t = notes.renameTag(id, name, color);
        cache.invalidate(companyId(), ReadCache.Domain.NOTES);
        return t;
    }
    public void deleteTag(int id) {
        notes.deleteTag(id);
        cache.invalidate(companyId(), ReadCache.Domain.NOTES);
    }
    public List<Folder> folders() {
        return cache.get(companyId(), ReadCache.Domain.NOTES, "folders", () -> List.copyOf(notes.folders()));
    }
    public Folder createFolder(String name, Integer parent) {
        Folder f = notes.createFolder(name, parent);
        cache.invalidate(companyId(), ReadCache.Domain.NOTES);
        return f;
    }
    public Folder renameFolder(int id, String name) {
        Folder f = notes.renameFolder(id, name);
        cache.invalidate(companyId(), ReadCache.Domain.NOTES);
        return f;
    }
    public Folder moveFolder(int id, Integer parent) {
        Folder f = notes.moveFolder(id, parent);
        cache.invalidate(companyId(), ReadCache.Domain.NOTES);
        return f;
    }
    public void deleteFolder(int id) {
        notes.deleteFolder(id);
        cache.invalidate(companyId(), ReadCache.Domain.NOTES);
    }

    // ---- note / card files -----------------------------------------------------
    public Map<Integer, List<String>> noteFileNames(List<Integer> noteIds) { return files.namesByNotes(noteIds); }
    public List<NoteFile> listNoteFiles(int noteId) { return files.list(noteId); }
    public Map<Integer, String> noteFileStates(int noteId) { return files.statesForNote(noteId); }
    public NoteFile addNoteFile(int noteId, String name, String ctype, byte[] data) {
        NoteFile f = files.add(noteId, name, ctype, data);
        cache.invalidate(companyId(), ReadCache.Domain.NOTES);
        return f;
    }
    public NoteFile mustNoteFile(int id) { return files.must(id); }
    public byte[] downloadNoteFile(int id) { return files.download(id); }
    public void removeNoteFile(int id) {
        files.remove(id);
        cache.invalidate(companyId(), ReadCache.Domain.NOTES);
    }
    public String noteFileState(int id) { return files.state(id); }
    public List<KanbanCardFile> listCardFiles(int cardId) { return files.listForCard(cardId); }
    public Map<Integer, String> cardFileStates(int cardId) { return files.statesForCard(cardId); }
    public KanbanCardFile addCardFile(int cardId, String name, String ctype, byte[] data) {
        return files.addForCard(cardId, name, ctype, data);
    }
    public KanbanCardFile mustCardFile(int id) { return files.mustCardFile(id); }
    public byte[] downloadCardFile(int id) { return files.downloadCardFile(id); }
    public void removeCardFile(int id) { files.removeCardFile(id); }
    public String cardFileState(int id) { return files.cardState(id); }

    /** Test hook: inject object store before first files use. */
    public Files filesForTest(ponto.model.ObjectStore store) {
        synchronized (lock) {
            Files f = files.raw(store);
            for (Service s : scopedServices.values()) s.files.adopt(this.files);
            return f;
        }
    }

    // ---- kanban ----------------------------------------------------------------
    public List<KanbanColumn> kanbanColumns() {
        return cache.get(companyId(), ReadCache.Domain.KANBAN, "cols", () -> List.copyOf(kanban.columns()));
    }
    public List<KanbanCard> kanbanCards() {
        return cache.get(companyId(), ReadCache.Domain.KANBAN, "cards", () -> List.copyOf(kanban.cards()));
    }
    public KanbanColumn createKanbanColumn(String name) {
        KanbanColumn c = kanban.createColumn(name);
        cache.invalidate(companyId(), ReadCache.Domain.KANBAN, ReadCache.Domain.DIARIO);
        return c;
    }
    public KanbanColumn updateKanbanColumn(int id, String name, Integer position) {
        KanbanColumn c = kanban.updateColumn(id, name, position);
        cache.invalidate(companyId(), ReadCache.Domain.KANBAN, ReadCache.Domain.DIARIO);
        return c;
    }
    public void deleteKanbanColumnOnly(int id) {
        kanban.deleteColumn(id);
        cache.invalidate(companyId(), ReadCache.Domain.KANBAN, ReadCache.Domain.DIARIO);
    }
    public KanbanCard createKanbanCard(String title, String body, int columnId) {
        KanbanCard c = kanban.createCard(title, body, columnId);
        cache.invalidate(companyId(), ReadCache.Domain.KANBAN, ReadCache.Domain.DIARIO);
        return c;
    }
    public KanbanCard updateKanbanCard(int id, String title, String body, String done, int revision) {
        KanbanCard c = kanban.updateCard(id, title, body, done, revision);
        cache.invalidate(companyId(), ReadCache.Domain.KANBAN, ReadCache.Domain.DIARIO);
        return c;
    }
    public KanbanCard moveKanbanCard(int id, int columnId, int position) {
        KanbanCard c = kanban.moveCard(id, columnId, position);
        cache.invalidate(companyId(), ReadCache.Domain.KANBAN, ReadCache.Domain.DIARIO);
        return c;
    }
    public KanbanCard blockKanbanCard(int id, String kind, String reason) {
        KanbanCard c = kanban.blockCard(id, kind, reason);
        cache.invalidate(companyId(), ReadCache.Domain.KANBAN, ReadCache.Domain.DIARIO);
        return c;
    }
    public KanbanCard unblockKanbanCard(int id) {
        KanbanCard c = kanban.unblockCard(id);
        cache.invalidate(companyId(), ReadCache.Domain.KANBAN, ReadCache.Domain.DIARIO);
        return c;
    }
    public KanbanCard kanbanCardById(int id) { return kanban.byId(id); }
    /** Distinct kanban card titles touched on the day ("kafeine", 5-day retention). */
    public List<String> kanbanActivity(java.time.LocalDate dia) {
        return kanban.activityOn(Str.iso(dia));
    }

    // ---- vault ---------------------------------------------------------------
    // The vault is one: a single master password opens every company's records.
    // The server only ever hands over sealed blobs — the key never exists here.
    public VaultMeta vaultMeta() { return vault.meta(); }
    public VaultMeta vaultCreate(String kdf, String salt, int iterations, String check) {
        return vault.create(kdf, salt, iterations, check);
    }
    public List<VaultItem> vaultItems(Integer companyFilter) { return vault.items(companyFilter); }
    public VaultItem vaultItemById(int id) { return vault.byId(id); }
    public VaultItem createVaultItem(int companyId, String blob) { return vault.create(companyId, blob); }
    public VaultItem updateVaultItem(int id, String blob, int revision) { return vault.update(id, blob, revision); }
    public void deleteVaultItem(int id) { vault.delete(id); }
    public VaultMeta vaultRekey(String kdf, String salt, int iterations, String check, List<VaultRewrap> items) {
        return vault.rekey(kdf, salt, iterations, check, items);
    }
    public List<VaultItem> createVaultItems(List<VaultDraft> drafts) { return vault.createAll(drafts); }
    public int vaultCount() { return vault.count(); }
    public int vaultCount(int companyId) { return vault.countCompany(companyId); }
    public int deleteVaultCompany(int companyId) { return vault.deleteCompany(companyId); }

    // ---- companies -------------------------------------------------------------
    public Company activeCompany() { return companies.active(); }
    public List<Company> listCompanies() { return companies.list(); }
    public Company companyById(int id) { return companies.byId(id); }
    public Company createCompany(String name, String off, String daily) {
        Company c = companies.create(name, off, daily);
        cache.invalidateDomain(ReadCache.Domain.COMPANY);
        return c;
    }
    public Company updateCompany(int id, String name, String off, String daily) {
        Company c = companies.update(id, name, off, daily);
        cache.invalidate(id, ReadCache.Domain.TIMESHEET, ReadCache.Domain.DIARIO);
        cache.invalidateDomain(ReadCache.Domain.COMPANY);
        return c;
    }
    public void deleteCompany(int id) {
        synchronized (lock) {
            companies.delete(id);
            scopedServices.remove(id);
        }
        cache.invalidateAll();
    }

    // ---- timesheet delegates ---------------------------------------------------
    public void registerOff() { timesheet.registerOff(); }
    public void registerOff(Consumer<String> bad) { timesheet.registerOff(bad); }
    public int count() { return timesheet.count(); }
    public LocalDate min() { return timesheet.min(); }
    public LocalDate max() { return timesheet.max(); }
    public int countOn(LocalDate d) { return timesheet.countOn(d); }
    public List<Entry> recent(int n, int skip) { return timesheet.recent(n, skip); }
    public List<Entry> search(String q) { return timesheet.search(q); }
    public List<Entry> byDay(String pattern, int n, int skip) { return timesheet.byDay(pattern, n, skip); }
    public int countDay(String pattern) { return timesheet.countDay(pattern); }
    public String dbPath() { return timesheet.dbPath(); }
    public String rawOff() { return timesheet.rawOff(); }
    public String rawWho() { synchronized (lock) { return db.cfg("who", ""); } }
    public boolean asked() { synchronized (lock) { return db.cfg("asked").equals("1"); } }
    public void markAsked() { synchronized (lock) { db.setCfg("asked", "1"); } }

    public String who() {
        synchronized (lock) {
            String saved = db.cfg("who", "");
            if (!saved.isEmpty()) return saved;
            String env = System.getenv("PONTO_USER");
            return env == null ? "" : env;
        }
    }

    public Warn inspect(Entry e, int skip) { return timesheet.inspect(e, skip); }
    public Entry create(LocalDate dia, LocalTime tin, LocalTime tout, String just,
                 boolean closeOpen, boolean confirmMidnight, boolean confirmOverlap) {
        Entry e = timesheet.create(dia, tin, tout, just, closeOpen, confirmMidnight, confirmOverlap);
        cache.invalidate(companyId(), ReadCache.Domain.TIMESHEET, ReadCache.Domain.DIARIO);
        return e;
    }
    public Entry update(int id, LocalDate dia, LocalTime tin, LocalTime tout, String just,
                 boolean confirmMidnight, boolean confirmOverlap) {
        Entry e = timesheet.update(id, dia, tin, tout, just, confirmMidnight, confirmOverlap);
        cache.invalidate(companyId(), ReadCache.Domain.TIMESHEET, ReadCache.Domain.DIARIO);
        return e;
    }
    public void delete(int id) {
        timesheet.delete(id);
        cache.invalidate(companyId(), ReadCache.Domain.TIMESHEET, ReadCache.Domain.DIARIO);
    }
    public int deleteDay(LocalDate d) {
        int n = timesheet.deleteDay(d);
        cache.invalidate(companyId(), ReadCache.Domain.TIMESHEET, ReadCache.Domain.DIARIO);
        return n;
    }
    public HomeView home() {
        return cache.get(companyId(), ReadCache.Domain.TIMESHEET, "home:" + Time.today(), timesheet::home);
    }
    public TimesheetApp.EntriesPage entriesRange(LocalDate from, LocalDate to, String query, int n, int skip) {
        String key = "er:" + Time.today() + ":" + from + ":" + to + ":" + (query == null ? "" : query) + ":" + n + ":" + skip;
        return cache.get(companyId(), ReadCache.Domain.TIMESHEET, key,
                () -> timesheet.entriesRange(from, to, query, n, skip));
    }
    public TimesheetApp.EntriesPage entriesList(String q, String filter, int n, int skip) {
        String key = "el:" + Time.today() + ":" + (q == null ? "" : q) + ":" + (filter == null ? "" : filter) + ":" + n + ":" + skip;
        return cache.get(companyId(), ReadCache.Domain.TIMESHEET, key,
                () -> timesheet.entriesList(q, filter, n, skip));
    }
    public TimesheetApp.BankSnapshot bankSnapshot(YearMonth ym, int months) {
        String key = "bank:" + Time.today() + ":" + ym + ":" + months;
        return cache.get(companyId(), ReadCache.Domain.TIMESHEET, key,
                () -> timesheet.bankSnapshot(ym, months));
    }
    public List<Entry> list(String q, String filter, int n, int skip) { return timesheet.list(q, filter, n, skip); }
    public int count(String q, String filter) { return timesheet.count(q, filter); }
    public Totals listTotals(String q, String filter) { return timesheet.listTotals(q, filter); }
    public List<Entry> listBetween(LocalDate from, LocalDate to, int n, int skip) {
        return timesheet.listBetween(from, to, n, skip);
    }
    public FilteredRange filteredRange(LocalDate from, LocalDate to, String query, int n, int skip) {
        return timesheet.filteredRange(from, to, query, n, skip);
    }
    public int countBetween(LocalDate from, LocalDate to) { return timesheet.countBetween(from, to); }
    public Totals totalsBetween(LocalDate from, LocalDate to) { return timesheet.totalsBetween(from, to); }
    public long overtimeBetween(LocalDate from, LocalDate to) { return timesheet.overtimeBetween(from, to); }
    public int dailyMinutes() { return timesheet.dailyMinutes(); }
    public long overtime(String q, String filter) { return timesheet.overtime(q, filter); }
    public long monthSaldo(YearMonth ym) { return timesheet.monthSaldo(ym); }
    public Bank bank() { return timesheet.bank(); }
    public Bank bank(YearMonth ym) { return timesheet.bank(ym); }
    public List<BankDay> bankDays(YearMonth ym) { return timesheet.bankDays(ym); }
    public List<MonthSaldo> history(YearMonth ym, int n) { return timesheet.history(ym, n); }
    public boolean falta(LocalDate d) { return timesheet.falta(d); }
    public void falta(LocalDate d, boolean on) {
        timesheet.falta(d, on);
        cache.invalidate(companyId(), ReadCache.Domain.TIMESHEET, ReadCache.Domain.DIARIO);
    }
    public List<LocalDate> faltas() { return timesheet.faltas(); }
    public boolean normal(LocalDate d) { return timesheet.normal(d); }
    public void normal(LocalDate d, boolean on) {
        timesheet.normal(d, on);
        cache.invalidate(companyId(), ReadCache.Domain.TIMESHEET, ReadCache.Domain.DIARIO);
    }
    public List<LocalDate> normais() { return timesheet.normais(); }
    public String normalWarn(LocalDate d) { return timesheet.normalWarn(d); }
    public String faltaWarn(LocalDate d) { return timesheet.faltaWarn(d); }
    public Entry byId(int id) { return timesheet.byId(id); }
    public List<Entry> on(LocalDate d) { return timesheet.on(d); }
    public String holiday(LocalDate d) { return timesheet.holiday(d); }
    public Report report(LocalDate from, LocalDate to) { return timesheet.report(from, to); }

    public Map<String, Object> diario(LocalDate dia) {
        String key = "diario:" + Str.iso(dia) + ":" + Time.today();
        return cache.get(companyId(), ReadCache.Domain.DIARIO, key, () -> {
            synchronized (lock) { return diario.snapshot(dia); }
        });
    }

    public DigestView gerarResumoDiario(LocalDate dia, Assist assist) {
        DigestView out;
        synchronized (lock) { out = diario.gerarResumo(dia, assist); }
        cache.invalidate(companyId(), ReadCache.Domain.DIARIO);
        return out;
    }

    public ConfigView config() {
        boolean storageReady = storageUp();
        synchronized (lock) {
            Company c = companies.active();
            Repo.Stats st = timesheet.stats();
            return new ConfigView(
                    who(), c.name(), c.id(), c.off(), c.daily(), dailyMinutes(),
                    maskDbUrl(db.url), st.count(),
                    Str.d(st.min()), Str.d(st.max()),
                    System.getProperty("java.version"), db.version(),
                    storageReady, files.pending(),
                    Files.maxUpload() / (1024 * 1024),
                    calendar.next(4));
        }
    }

    public ConfigView setConfig(String who, String off, boolean vacuum) {
        return setConfig(who, off, null, vacuum, s -> { });
    }

    public ConfigView setConfig(String who, String off, boolean vacuum, Consumer<String> bad) {
        return setConfig(who, off, null, vacuum, bad);
    }

    public ConfigView setConfig(String who, String off, String daily, boolean vacuum, Consumer<String> bad) {
        synchronized (lock) {
            db.transaction(() -> {
                if (who != null) db.setCfg("who", who.equals("-") ? "" : who);
                Company cur = companies.active();
                String nextOff = off == null ? null : (off.equals("-") ? "" : off);
                if (nextOff != null || daily != null) companies.update(cur.id(), cur.name(), nextOff, daily);
                if (nextOff != null) offDates(nextOff, bad);
                db.setCfg("asked", "1");
                return null;
            });
            if (vacuum) db.vacuum();
        }
        cache.invalidateDomain(ReadCache.Domain.COMPANY, ReadCache.Domain.TIMESHEET, ReadCache.Domain.DIARIO);
        return config();
    }

    public Company activate(int id) {
        synchronized (lock) {
            companies.activate(id);
            Company active = companies.active();
            cache.invalidateDomain(ReadCache.Domain.COMPANY);
            return active;
        }
    }

    public static String dailyLabel(int mins) {
        String v = String.format(java.util.Locale.ROOT, "%.2f", mins / 60.0);
        if (v.endsWith("00")) v = v.substring(0, v.length() - 3);
        else if (v.endsWith("0")) v = v.substring(0, v.length() - 1);
        return v;
    }

    public static List<LocalDate> offDates(String raw) { return offDates(raw, s -> {}); }

    public static List<LocalDate> offDates(String raw, Consumer<String> bad) {
        List<LocalDate> r = new ArrayList<>();
        if (raw == null || raw.isBlank() || raw.equals("-")) return r;
        for (String s : raw.split("[;, ]+")) {
            if (s.isBlank()) continue;
            try { r.add(Parse.date(s)); }
            catch (Bad ignored) { bad.accept(s); }
        }
        return r;
    }

    public static int parseDailyMins(String raw) {
        if (raw == null || raw.isBlank() || raw.equals("-")) return DEFAULT_DAILY_MINS;
        String s = raw.trim().replace(',', '.');
        int mins;
        if (s.contains(":")) {
            String[] p = s.split(":");
            if (p.length != 2) throw new Bad("jornada: '" + raw + "'");
            int h = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim());
            if (m >= 60) throw new Bad("jornada: '" + raw + "'");
            mins = h * 60 + m;
        } else {
            double v;
            try { v = Double.parseDouble(s); }
            catch (NumberFormatException e) { throw new Bad("jornada: '" + raw + "'"); }
            if (v < 0) throw new Bad("jornada: '" + raw + "'");
            mins = (int) Math.round(v * 60.0);
        }
        if (mins < 30 || mins > 24 * 60) throw new Bad("jornada: '" + raw + "'");
        return mins;
    }

    public static LocalDate parseDate(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        return Parse.date(raw);
    }

    public static LocalTime parseTime(String raw) {
        if (raw == null || raw.isBlank() || raw.equals("-")) return null;
        return Parse.time(raw);
    }

    public Map<String, Object> httpClientCollection() { return httpClient.load(); }

    public Map<String, Object> saveHttpClientCollection(String blobJson) {
        return httpClient.save(blobJson);
    }
}
