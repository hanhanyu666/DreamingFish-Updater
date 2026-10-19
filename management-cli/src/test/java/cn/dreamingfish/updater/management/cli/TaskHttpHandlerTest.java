package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.*;
import cn.dreamingfish.updater.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskHttpHandlerTest {
    @TempDir Path temporary;
    final JsonCodec json=new JsonCodec();final HttpClient client=HttpClient.newHttpClient();
    private ManagementCli root()throws Exception {
        var root=new ManagementCli(temporary.resolve("home/management-settings.json"),new StringReader(""));
        Path source=Files.createDirectories(temporary.resolve("source"));Files.writeString(source.resolve("a.txt"),"content");
        root.services().projects().create("demo","Demo",source,"http://127.0.0.1:8080",Branding.empty(),ProjectRules.defaults());return root;
    }
    private HttpResponse<String> send(URI base,String token,String path,String method,String body,String id)throws Exception {
        var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10)).header("Origin",base.toString()).header("Content-Type","application/json");
        if(token!=null)builder.header("X-DFS-Token",token);if(id!=null)builder.header("Prefer","respond-async").header("X-DFS-Request-Id",id);
        return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private String session(URI base)throws Exception{return json.read(send(base,null,"/api/session","GET",null,null).body().getBytes(),Map.class).get("token").toString();}
    private ApplicationTasks.Snapshot finish(URI base,String token,String id)throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(true){var response=send(base,token,"/api/tasks/"+id,"GET",null,null);assertEquals(200,response.statusCode(),response.body());var task=json.read(response.body().getBytes(),ApplicationTasks.Snapshot.class);if(task.state()==ApplicationTasks.State.SUCCEEDED||task.state()==ApplicationTasks.State.FAILED||task.state()==ApplicationTasks.State.UNKNOWN)return task;if(System.nanoTime()>deadline)fail("Task did not finish");Thread.sleep(25);}
    }
    @Test void queuedHttpTaskCanBeQueriedAndCancelledWithoutOccupyingAnHttpWorker()throws Exception {
        var root=root();var tasks=root.services().tasks();CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);List<String> blocked=new ArrayList<>();
        try(var server=new AdminWebServer(root,new InetSocketAddress("127.0.0.1",0))){
            server.start();URI base=URI.create("http://127.0.0.1:"+server.address().getPort());String token=session(base);
            try {
                for(int i=0;i<2;i++){String id=UUID.randomUUID().toString();blocked.add(id);tasks.submit(id,"demo","test-hold",null,id,false,()->{entered.countDown();release.await();return 1;});}
                assertTrue(entered.await(5,TimeUnit.SECONDS));String id=UUID.randomUUID().toString();
                assertEquals(202,send(base,token,"/api/projects/demo/scan","POST","{}",id).statusCode());
                var queued=send(base,token,"/api/tasks/"+id,"GET",null,null);assertEquals(ApplicationTasks.State.QUEUED,json.read(queued.body().getBytes(),ApplicationTasks.Snapshot.class).state());
                assertEquals(200,send(base,token,"/api/tasks/"+id,"DELETE",null,null).statusCode());assertEquals(ApplicationTasks.State.CANCELLED,tasks.require(id).state());
                assertThrows(ManagementException.class,()->root.services().scanner().load("demo"));
            }finally{release.countDown();for(String id:blocked)tasks.await(id);}
        }
    }
    @Test void duplicateAsyncRequestRunsOnceAndLostAcknowledgementRemainsQueryable()throws Exception {
        var root=root();
        try(var server=new AdminWebServer(root,new InetSocketAddress("127.0.0.1",0))){
            server.start();URI base=URI.create("http://127.0.0.1:"+server.address().getPort());String token=session(base),id=UUID.randomUUID().toString();
            assertEquals(202,send(base,token,"/api/projects/demo/scan","POST","{}",id).statusCode());var first=finish(base,token,id);assertEquals(ApplicationTasks.State.SUCCEEDED,first.state());
            assertEquals(202,send(base,token,"/api/projects/demo/scan","POST","{}",id).statusCode());assertEquals(first.result(),finish(base,token,id).result());
            String lost=UUID.randomUUID().toString();
            try(Socket socket=new Socket("127.0.0.1",server.address().getPort())){
                String request="POST /api/projects/demo/scan HTTP/1.1\r\nHost: 127.0.0.1:"+server.address().getPort()+"\r\nOrigin: "+base+"\r\nX-DFS-Token: "+token+"\r\nPrefer: respond-async\r\nX-DFS-Request-Id: "+lost+"\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}";
                socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.UTF_8));socket.getOutputStream().flush();
            }
            // Wait for admission; a closed response socket must not cancel an admitted application use case.
            long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
            while(root.services().tasks().list().stream().noneMatch(task->task.id().equals(lost))){if(System.nanoTime()>deadline)fail("Lost request was not admitted");Thread.sleep(25);}
            assertEquals(ApplicationTasks.State.SUCCEEDED,finish(base,token,lost).state());
            assertEquals(1,root.serviceInitializations());
        }
    }
}
