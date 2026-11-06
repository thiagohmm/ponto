package ponto.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ponto.Problema;
import ponto.StorageFailure;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentsTest {
    @TempDir Path dir;
    static class Store implements ObjectStore {
        final Map<String,byte[]> data=new ConcurrentHashMap<>();
        volatile boolean fail;
        volatile CountDownLatch entered,release;
        public boolean up(){return !fail;}
        public void ensureBucket(){check();}
        public void put(String key,byte[] bytes,String type){
            if(entered!=null){entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            check();data.put(key,bytes);
        }
        public byte[] get(String key){check();return data.get(key);}
        public void delete(String key){check();data.remove(key);}
        private void check(){if(fail)throw new Problema("offline");}
    }
    @Test void failedUploadSurvivesRestartAndRetriesAreBounded() {
        Path file=dir.resolve("queue.db");Store store=new Store();store.fail=true;
        int note;
        try(Db db=Db.open(file.toString());Files files=new Files(db,db.lock,store)){
            note=new Service(db).notes().create("nota","").id();
            assertThrows(StorageFailure.class,()->files.add(note,"teste.txt","text/plain","abc".getBytes()));
            assertEquals(1,files.pending());
            assertEquals("failed",files.state(files.list(note).getFirst().id()));
            files.reconcile();files.reconcile();files.reconcile();
            try(var q=db.conn().createStatement();var rs=q.executeQuery("SELECT attempts FROM object_job")){assertEquals(3,rs.getInt(1));}
            catch(Exception e){fail(e);}
        }
        store.fail=false;
        try(Db db=Db.open(file.toString());Files files=new Files(db,db.lock,store)){
            files.retry();files.reconcile();
            // retry also dispatches asynchronously; the per-object lock makes both paths safe.
            var f=files.list(note).getFirst();
            assertEquals("ready",files.state(f.id()));assertEquals(0,files.pending());
            assertArrayEquals("abc".getBytes(),files.download(f.id()));
            store.fail=true;files.remove(f.id());
            assertTrue(files.list(note).isEmpty());assertEquals(1,files.pending());
            store.fail=false;files.reconcile();assertEquals(0,files.pending());assertTrue(store.data.isEmpty());
        }
    }
    @Test void slowStorageDoesNotHoldDatabaseAndDeleteDuringUploadCleansObject() throws Exception {
        Store store=new Store();store.entered=new CountDownLatch(1);store.release=new CountDownLatch(1);
        try(Db db=Db.open(dir.resolve("slow.db").toString());Files files=new Files(db,db.lock,store);
            var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var service=new Service(db);var note=service.notes().create("nota","");
            var upload=pool.submit(()->files.add(note.id(),"teste","text/plain",new byte[]{1}));
            assertTrue(store.entered.await(3,TimeUnit.SECONDS));
            assertEquals(0,pool.submit(() -> { return service.count(); }).get(1,TimeUnit.SECONDS));
            db.transaction(()->{files.scheduleNoteDeletion(note.id());service.notes().delete(note.id());return null;});
            store.release.countDown();
            assertThrows(java.util.concurrent.ExecutionException.class,upload::get);
            files.reconcile();
            assertTrue(store.data.isEmpty());assertEquals(0,files.pending());
        }
    }
    @Test void noteAndCleanupIntentCommitTogether() {
        Store store=new Store();
        try(Db db=Db.open(dir.resolve("delete.db").toString());Files files=new Files(db,db.lock,store)) {
            var service=new Service(db);var note=service.notes().create("nota","");
            var f=files.add(note.id(),"ok","text/plain",new byte[]{2});
            assertThrows(Problema.class,()->db.transaction(()->{
                files.scheduleNoteDeletion(note.id());service.notes().delete(note.id());throw new Problema("rollback");
            }));
            assertEquals(note.id(),service.notes().byId(note.id()).id());
            assertEquals(f.id(),files.list(note.id()).getFirst().id());assertEquals(0,files.pending());
        }
    }
}
