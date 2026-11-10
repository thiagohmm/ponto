package ponto.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ponto.Conflict;
import ponto.NotFound;
import ponto.Problema;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class IntegrityTest {
    @TempDir Path dir;
    private Db open(String name) { return Db.open(dir.resolve(name).toString()); }

    @Test void legacyUpgradePreservesDataAndIsRepeatable() throws Exception {
        Path file = dir.resolve("legacy.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file); var q = c.createStatement()) {
            q.execute("CREATE TABLE ponto(id INTEGER PRIMARY KEY AUTOINCREMENT,dia TEXT NOT NULL,tin TEXT,tout TEXT,just TEXT NOT NULL DEFAULT '',crea TEXT NOT NULL DEFAULT '')");
            q.execute("INSERT INTO ponto(id,dia,tin,tout,just) VALUES(7,'2026-05-20','08:00','16:00','legado')");
            q.execute("INSERT INTO ponto(id,dia) VALUES(99,'2026-05-21')");
            q.execute("DELETE FROM ponto WHERE id=99");
            q.execute("CREATE TABLE cfg(k TEXT PRIMARY KEY,v TEXT NOT NULL DEFAULT '')");
            q.execute("INSERT INTO cfg VALUES('who','Ana'),('daily','6')");
        }
        for (int i=0;i<2;i++) try (Db db = Db.open(file.toString())) {
            Service s = new Service(db);
            assertEquals("legado", s.byId(7).just());
            assertEquals(360, s.dailyMinutes());
            assertEquals(1,s.count());
            assertEquals(Migrations.VERSION, version(db));
        }
        try (Db db = Db.open(file.toString())) {
            var s = new Service(db);
            assertTrue(s.create(LocalDate.of(2026,5,22),null,null,"novo",false,false,false).id()>99);
        }
    }

    @Test void failedMigrationRollsBackSchemaAndRows() throws Exception {
        Path file=dir.resolve("broken.db");
        try (var c=DriverManager.getConnection("jdbc:sqlite:"+file); var q=c.createStatement()) {
            for(String sql:Db.SCHEMA) q.execute(sql);
            q.execute("INSERT INTO note(id,title,folder_id) VALUES(3,'preservar',999)");
        }
        assertThrows(Problema.class,()->Db.open(file.toString()));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+file);var q=c.createStatement()) {
            try(var rs=q.executeQuery("PRAGMA user_version")){assertEquals(0,rs.getInt(1));}
            try(var rs=q.executeQuery("SELECT title,folder_id FROM note WHERE id=3")){
                assertEquals("preservar",rs.getString(1));assertEquals(999,rs.getInt(2));
            }
            try(var rs=q.executeQuery("SELECT count(*) FROM sqlite_master WHERE name LIKE '%_upgrade'")){assertEquals(0,rs.getInt(1));}
        }
    }

    @Test void independentContextsAndCalendarsSurviveConcurrentAccess() throws Exception {
        try(Db db=open("scope.db")) {
            Service root=new Service(db);
            int a=root.companyId(),b=root.companies().create("B","","8").id();
            Service sa=root.forCompany(a),sb=root.forCompany(b);
            sa.setConfig(null,"20/05/2026","6",false,x->{});
            var day=LocalDate.of(2026,5,20);
            var note=sa.notes().create("A","A");
            assertThrows(NotFound.class,()->sb.notes().delete(note.id()));
            assertEquals(1,sa.notes().list(null).size());
            try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                var tasks=new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for(int i=0;i<40;i++) {
                    Service s=i%2==0?sa:sb;
                    tasks.add(pool.submit(()->{
                        assertEquals(s==sa,s.holiday(day).contains("sua folga"));
                        assertEquals(s==sa?360:480,s.dailyMinutes());
                        s.create(day,null,null,s==sa?"A":"B",false,false,false);
                    }));
                }
                for(var task:tasks)task.get();
            }
            assertEquals(20,sa.count());assertEquals(20,sb.count());
            assertEquals(a,root.companyId());
            root.closeResources();
        }
    }

    @Test void noteCreationTagsAndFolderUpdatesAreAtomic() {
        try(Db db=open("atomic.db")) {
            var s=new Service(db);
            var tag=s.notes().createTag("tag",null);
            var n=s.notes().create("nota","texto",null,List.of(tag.id()));
            assertThrows(NotFound.class,()->s.notes().setTags(n.id(),List.of(999)));
            assertEquals(List.of(tag.id()),s.notes().byId(n.id()).tagIds());
            assertThrows(NotFound.class,()->s.notes().create("incompleta","",null,List.of(999)));
            assertEquals(1,s.notes().list(null).size());
            var f=s.notes().createFolder("original",null);
            assertThrows(Problema.class,()->s.transaction(()->{
                s.notes().renameFolder(f.id(),"alterado");return s.notes().moveFolder(f.id(),999);
            }));
            assertEquals("original",s.notes().folders().getFirst().name());
        }
    }

    @Test void revisionConflictPreservesNewerText() {
        try(Db db=open("revision.db")) {
            var notes=new Service(db).notes();
            var n=notes.create("titulo","primeiro");
            var next=notes.update(n.id(),"titulo","segundo",n.revision());
            assertEquals(n.revision()+1,next.revision());
            assertThrows(Conflict.class,()->notes.update(n.id(),"titulo","antigo",n.revision()));
            assertEquals("segundo",notes.byId(n.id()).body());
        }
    }

    @Test void backupIncludesWalAndCanBeRestored() {
        Path copy=dir.resolve("snapshot.db");
        try(Db db=open("live.db")) {
            new Service(db).notes().create("WAL","confirmado");
            db.backup(copy);
            new Service(db).notes().create("depois","fora do backup");
        }
        try(Db db=Db.open(copy.toString())) {
            var notes=new Service(db).notes().list(null);
            assertEquals(1,notes.size());assertEquals("confirmado",notes.getFirst().body());
        }
    }

    @Test void clockAndBalanceBoundariesAreDeterministic() throws Exception {
        Clock fixed=Clock.fixed(Instant.parse("2026-06-01T01:00:00Z"),ZoneId.of("America/Sao_Paulo"));
        try(var clock=Time.using(fixed);Db db=open("clock.db")) {
            assertEquals(LocalDate.of(2026,5,31),Time.today());
            assertEquals("2026-05",new Service(db).bank().ym().toString());
            assertEquals(Time.today(),Parse.date("today"));
        }
        for(int raw:new int[]{-6,-5,-4,0,4,5,6}) {
            assertEquals(Math.abs(raw)<=5?0:raw,BankRules.day(480+raw,480,false,false,false,true));
            assertEquals(raw,BankRules.day(480+raw,480,true,false,false,true));
        }
        assertEquals(60,BankRules.day(60,480,false,false,true,true));
        assertEquals(0,BankRules.day(0,480,false,true,true,false));
        assertEquals(-480,BankRules.day(0,480,true,false,true,false));
    }

    private int version(Db db)throws Exception {
        try(var q=db.conn().createStatement();var rs=q.executeQuery("PRAGMA user_version")){return rs.getInt(1);}
    }
}
