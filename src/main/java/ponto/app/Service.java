package ponto.app;

import ponto.Bad;
import ponto.infra.Assist;
import ponto.infra.CompaniesStore;
import ponto.infra.Db;
import ponto.infra.Files;
import ponto.infra.KanbanStore;
import ponto.infra.NotesStore;
import ponto.model.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final Holidays calendar;
    private final CompanyApp companies;
    private final NotesApp notes;
    private final KanbanApp kanban;
    private final FilesApp files;
    private final TimesheetApp timesheet;
    private final DiarioApp diario;

    public Service(Db db) {
        this.db = db;
        this.lock = db.lock;
        this.companies = new CompanyApp(new CompaniesStore(db, lock));
        this.calendar = new Holidays(() -> offDates(companies.active().off()));
        this.notes = new NotesApp(new NotesStore(db, lock));
        this.kanban = new KanbanApp(new KanbanStore(db, lock));
        this.files = new FilesApp(db, lock);
        this.timesheet = new TimesheetApp(db, lock, calendar, companies::active, this::who);
        this.diario = new DiarioApp(db, lock, timesheet.repo(), calendar,
                timesheet::dailyMinutes, timesheet::falta, timesheet::normal);
    }

    /** Scoped view for one company; reuses Files worker/storage. */
    public Service forCompany(int id) {
        synchronized (lock) {
            companies.byId(id);
            Service scoped = new Service(db.forCompany(id));
            scoped.files.adopt(this.files);
            return scoped;
        }
    }

    public <T> T transaction(java.util.function.Supplier<T> work) { return db.transaction(work); }
    public boolean healthy() { return db.healthy(); }
    public LocalDate today() { return Time.today(); }
    public int companyId() { synchronized (lock) { return db.company(); } }
    public java.nio.file.Path dataDir() { return db.dataDir; }

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
        files.kick();
    }

    public void deleteKanbanCard(int id) {
        db.transaction(() -> {
            kanban.byId(id);
            files.scheduleCardDeletion(id);
            kanban.deleteCard(id);
            return null;
        });
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
    public List<Note> listNotes(String q) { return notes.list(q); }
    public Note noteById(int id) { return notes.byId(id); }
    public Note createNote(String title, String body) { return notes.create(title, body); }
    public Note createNote(String title, String body, Integer folderId, List<Integer> tagIds) {
        return notes.create(title, body, folderId, tagIds);
    }
    public Note updateNote(int id, String title, String body, int revision) {
        return notes.update(id, title, body, revision);
    }
    public Note moveNote(int id, Integer folderId) { return notes.move(id, folderId); }
    public Note setNoteTags(int id, List<Integer> tagIds) { return notes.setTags(id, tagIds); }
    public Note addNoteTag(int id, int tagId) { return notes.addTag(id, tagId); }
    public List<Tag> tags() { return notes.tags(); }
    public Tag createTag(String name, String color) { return notes.createTag(name, color); }
    public Tag renameTag(int id, String name, String color) { return notes.renameTag(id, name, color); }
    public void deleteTag(int id) { notes.deleteTag(id); }
    public List<Folder> folders() { return notes.folders(); }
    public Folder createFolder(String name, Integer parent) { return notes.createFolder(name, parent); }
    public Folder renameFolder(int id, String name) { return notes.renameFolder(id, name); }
    public Folder moveFolder(int id, Integer parent) { return notes.moveFolder(id, parent); }
    public void deleteFolder(int id) { notes.deleteFolder(id); }

    // ---- note / card files -----------------------------------------------------
    public Map<Integer, List<String>> noteFileNames(List<Integer> noteIds) { return files.namesByNotes(noteIds); }
    public List<NoteFile> listNoteFiles(int noteId) { return files.list(noteId); }
    public NoteFile addNoteFile(int noteId, String name, String ctype, byte[] data) {
        return files.add(noteId, name, ctype, data);
    }
    public NoteFile mustNoteFile(int id) { return files.must(id); }
    public byte[] downloadNoteFile(int id) { return files.download(id); }
    public void removeNoteFile(int id) { files.remove(id); }
    public String noteFileState(int id) { return files.state(id); }
    public List<KanbanCardFile> listCardFiles(int cardId) { return files.listForCard(cardId); }
    public KanbanCardFile addCardFile(int cardId, String name, String ctype, byte[] data) {
        return files.addForCard(cardId, name, ctype, data);
    }
    public KanbanCardFile mustCardFile(int id) { return files.mustCardFile(id); }
    public byte[] downloadCardFile(int id) { return files.downloadCardFile(id); }
    public void removeCardFile(int id) { files.removeCardFile(id); }
    public String cardFileState(int id) { return files.cardState(id); }

    /** Test hook: inject object store before first files use. */
    public Files filesForTest(ponto.model.ObjectStore store) { return files.raw(store); }

    // ---- kanban ----------------------------------------------------------------
    public List<KanbanColumn> kanbanColumns() { return kanban.columns(); }
    public List<KanbanCard> kanbanCards() { return kanban.cards(); }
    public KanbanColumn createKanbanColumn(String name) { return kanban.createColumn(name); }
    public KanbanColumn updateKanbanColumn(int id, String name, Integer position) {
        return kanban.updateColumn(id, name, position);
    }
    public void deleteKanbanColumnOnly(int id) { kanban.deleteColumn(id); }
    public KanbanCard createKanbanCard(String title, String body, int columnId) {
        return kanban.createCard(title, body, columnId);
    }
    public KanbanCard updateKanbanCard(int id, String title, String body, String done, int revision) {
        return kanban.updateCard(id, title, body, done, revision);
    }
    public KanbanCard moveKanbanCard(int id, int columnId, int position) {
        return kanban.moveCard(id, columnId, position);
    }
    public KanbanCard blockKanbanCard(int id, String kind, String reason) {
        return kanban.blockCard(id, kind, reason);
    }
    public KanbanCard unblockKanbanCard(int id) { return kanban.unblockCard(id); }
    public KanbanCard kanbanCardById(int id) { return kanban.byId(id); }

    // ---- companies -------------------------------------------------------------
    public Company activeCompany() { return companies.active(); }
    public List<Company> listCompanies() { return companies.list(); }
    public Company companyById(int id) { return companies.byId(id); }
    public Company createCompany(String name, String off, String daily) {
        return companies.create(name, off, daily);
    }
    public Company updateCompany(int id, String name, String off, String daily) {
        return companies.update(id, name, off, daily);
    }
    public void deleteCompany(int id) { companies.delete(id); }

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
        return timesheet.create(dia, tin, tout, just, closeOpen, confirmMidnight, confirmOverlap);
    }
    public Entry update(int id, LocalDate dia, LocalTime tin, LocalTime tout, String just,
                 boolean confirmMidnight, boolean confirmOverlap) {
        return timesheet.update(id, dia, tin, tout, just, confirmMidnight, confirmOverlap);
    }
    public void delete(int id) { timesheet.delete(id); }
    public int deleteDay(LocalDate d) { return timesheet.deleteDay(d); }
    public HomeView home() { return timesheet.home(); }
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
    public void falta(LocalDate d, boolean on) { timesheet.falta(d, on); }
    public List<LocalDate> faltas() { return timesheet.faltas(); }
    public boolean normal(LocalDate d) { return timesheet.normal(d); }
    public void normal(LocalDate d, boolean on) { timesheet.normal(d, on); }
    public List<LocalDate> normais() { return timesheet.normais(); }
    public String normalWarn(LocalDate d) { return timesheet.normalWarn(d); }
    public String faltaWarn(LocalDate d) { return timesheet.faltaWarn(d); }
    public Entry byId(int id) { return timesheet.byId(id); }
    public List<Entry> on(LocalDate d) { return timesheet.on(d); }
    public String holiday(LocalDate d) { return timesheet.holiday(d); }
    public Report report(LocalDate from, LocalDate to) { return timesheet.report(from, to); }

    public Map<String, Object> diario(LocalDate dia) {
        synchronized (lock) { return diario.snapshot(dia); }
    }

    public DigestView gerarResumoDiario(LocalDate dia, Assist assist) {
        synchronized (lock) { return diario.gerarResumo(dia, assist); }
    }

    public ConfigView config() {
        boolean storageReady = storageUp();
        synchronized (lock) {
            timesheet.registerOff();
            Company c = companies.active();
            return new ConfigView(
                    who(), c.name(), c.id(), c.off(), c.daily(), dailyMinutes(),
                    maskDbUrl(db.url), timesheet.count(),
                    Str.d(timesheet.min()), Str.d(timesheet.max()),
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
        return config();
    }

    public Company activate(int id) {
        synchronized (lock) {
            companies.activate(id);
            timesheet.registerOff();
            return companies.active();
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
}
