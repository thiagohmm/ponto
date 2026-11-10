package ponto.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ponto.model.*;
import ponto.view.Json3;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class WebTest {
    @TempDir Path dir;
    private final HttpClient client=HttpClient.newHttpClient();
    private String base;
    private HttpResponse<String> call(String method,String path,String body)throws Exception {
        var req=HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/json")
                .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(req,HttpResponse.BodyHandlers.ofString());
    }
    private Map<?,?> json(String body){return new Json3().fromJsonString(body,Map.class);}
    @Test void contextsErrorsRevisionAndExports()throws Exception {
        try(Db db=Db.open(dir.resolve("web.db").toString())) {
            var service=new Service(db);
            int a=service.companyId(),b=service.companies().create("B","","8").id();
            Service sa=service.forCompany(a),sb=service.forCompany(b);
            var day=LocalDate.of(2026,5,20);
            var entry=sa.create(day,LocalTime.of(8,0),LocalTime.of(16,0),"web-A",false,false,false);
            var note=sa.notes().create("A","primeiro");
            sb.create(day,LocalTime.of(9,0),LocalTime.of(10,0),"web-B",false,false,false);
            var app=new Web(service,0).application().start("127.0.0.1",0);
            base="http://127.0.0.1:"+app.port();
            try {
                var health=call("GET","/api/health",null);
                assertEquals(200,health.statusCode());
                assertEquals("nosniff",health.headers().firstValue("X-Content-Type-Options").orElse(""));
                assertEquals("DENY",health.headers().firstValue("X-Frame-Options").orElse(""));
                assertEquals("no-referrer",health.headers().firstValue("Referrer-Policy").orElse(""));
                assertEquals("camera=(), microphone=(), geolocation=()",health.headers().firstValue("Permissions-Policy").orElse(""));
                assertTrue(health.headers().firstValue("Content-Security-Policy").orElse("").startsWith("default-src 'self'"));
                assertTrue(health.headers().firstValue("Strict-Transport-Security").orElse("").contains("max-age=31536000"));
                assertTrue(health.headers().firstValue("Server").orElse("").isEmpty()||!health.headers().firstValue("Server").orElse("").contains("Jetty"));
                assertEquals(200,call("GET","/api/companies",null).statusCode());
                assertEquals(400,call("GET","/api/entries",null).statusCode());
                assertEquals(400,call("GET","/api/entries?companyId=no",null).statusCode());
                assertEquals(404,call("GET","/api/entries/"+entry.id()+"?companyId="+b,null).statusCode());
                assertEquals(404,call("DELETE","/api/notes/"+note.id()+"?companyId="+b,null).statusCode());
                assertEquals(404,call("GET","/api/notes/"+note.id()+"/files/999/download?companyId="+b,null).statusCode());
                assertEquals("primeiro",sa.notes().byId(note.id()).body());
                var invalid=call("POST","/api/entries?companyId="+a,"{invalid");
                assertEquals(400,invalid.statusCode());assertNotNull(invalid.headers().firstValue("X-Request-ID").orElse(null));
                String path="/api/notes/"+note.id()+"?companyId="+a;
                assertEquals(400,call("PUT",path,"{\"title\":\"A\",\"body\":\"sem revisão\"}").statusCode());
                assertEquals(200,call("PUT",path,"{\"title\":\"A\",\"body\":\"segundo\",\"revision\":1}").statusCode());
                assertEquals(409,call("PUT",path,"{\"title\":\"A\",\"body\":\"antigo\",\"revision\":1}").statusCode());
                assertEquals("segundo",sa.notes().byId(note.id()).body());
                assertEquals(409,call("POST","/api/entries?companyId="+a,
                    "{\"dia\":\"2026-05-20\",\"tin\":\"09:00\",\"tout\":\"10:00\"}").statusCode());
                try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                    var tasks=new java.util.ArrayList<java.util.concurrent.Future<?>>();
                    for(int i=0;i<20;i++) {
                        int company=i%2==0?a:b;
                        tasks.add(pool.submit(()->{
                            var response=call("GET","/api/entries?companyId="+company,null);
                            assertEquals(200,response.statusCode());assertTrue(response.body().contains(company==a?"web-A":"web-B"));
                            assertFalse(response.body().contains(company==a?"web-B":"web-A"));return null;
                        }));
                    }
                    for(var task:tasks)task.get();
                }
                assertEquals(a,service.companyId());
                assertEquals(200,call("PUT","/api/companies/active?companyId="+a,"{\"id\":"+b+"}").statusCode());
                assertEquals(a,service.companyId());
                var sum=json(call("GET","/api/sum?from=2026-05-20&to=2026-05-20&companyId="+a,null).body());
                assertEquals("8:00",sum.get("total"));
                String csv=call("GET","/api/export/csv?from=2026-05-20&to=2026-05-20&companyId="+a,null).body();
                assertTrue(csv.contains("8.00"));assertTrue(csv.contains("web-A"));assertFalse(csv.contains("web-B"));
                for(String kind:List.of("pdf","xls")) {
                    var request=HttpRequest.newBuilder(URI.create(base+"/api/export/"+kind+"?from=2026-05-20&to=2026-05-20&companyId="+a)).build();
                    var response=client.send(request,HttpResponse.BodyHandlers.ofByteArray());
                    assertEquals(200,response.statusCode());
                    if(kind.equals("pdf"))try(var pdf=org.apache.pdfbox.Loader.loadPDF(response.body())) {
                        assertTrue(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf).contains("web-A"));
                    }else try(var workbook=new org.apache.poi.hssf.usermodel.HSSFWorkbook(new java.io.ByteArrayInputStream(response.body()))) {
                        assertTrue(workbook.getNumberOfSheets()>0);
                        var text=new org.apache.poi.hssf.extractor.ExcelExtractor(workbook).getText();
                        assertTrue(text.contains("web-A"));assertTrue(text.contains("8.00")||text.contains("8:00"));
                    }
                }
            }finally{app.stop();}
        }
    }
}
