package cn.dreamingfish.updater.management;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicFileServerLimitsTest {
    @TempDir Path temporary;
    @Test void emptyHealthRequestsKeepTheAdvertisedConnectionReusable() throws Exception {
        var f=new ManagementFixture(temporary.resolve("keepalive"));
        try(var server=new PublicFileServer(f.database,f.objects,new InetSocketAddress("127.0.0.1",0));
            var socket=new Socket()) {
            server.start();socket.connect(server.address());socket.setSoTimeout(2000);
            for(int i=0;i<3;i++) {
                socket.getOutputStream().write("GET /healthz HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                var header=new java.io.ByteArrayOutputStream();int value;
                while(!header.toString(java.nio.charset.StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                    value=socket.getInputStream().read();assertNotEquals(-1,value,"server closed a connection advertised as reusable");header.write(value);
                }
                String headers=header.toString(java.nio.charset.StandardCharsets.US_ASCII);
                assertTrue(headers.startsWith("HTTP/1.1 200"),headers);
                var length=java.util.regex.Pattern.compile("(?im)^Content-length: (\\d+)").matcher(headers);
                assertTrue(length.find(),headers);int bytes=Integer.parseInt(length.group(1));
                assertEquals(bytes,socket.getInputStream().readNBytes(bytes).length);
            }
        }
    }
    private Path object(ManagementFixture f)throws Exception {
        Path file=temporary.resolve("large.bin");
        try(var output=new java.io.RandomAccessFile(file.toFile(),"rw")){output.setLength(32L*1024*1024);}
        return f.objects.importFile(file).path();
    }
    @Test void sixtyFourSlowReadersDoNotStarveHealthAndExcessDownloadsAreRetryable()throws Exception {
        var f=new ManagementFixture(temporary.resolve("fixture"));Path object=object(f);String hash=object.getFileName().toString();
        List<Socket> sockets=new ArrayList<>();
        try(var server=new PublicFileServer(f.database,f.objects,new InetSocketAddress("127.0.0.1",0),PublicFileServer.Limits.defaults())) {
            server.start();int port=server.address().getPort();
            try {
                for(int i=0;i<64;i++){
                    Socket socket=new Socket();socket.setReceiveBufferSize(1024);socket.connect(new InetSocketAddress("127.0.0.1",port),1000);socket.setSoTimeout(2000);sockets.add(socket);
                    socket.getOutputStream().write(("GET /v1/objects/sha256/"+hash+" HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                }
                try(var client=HttpClient.newHttpClient()){
                    var start=System.nanoTime();var health=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/healthz")).timeout(Duration.ofSeconds(2)).build(),HttpResponse.BodyHandlers.ofString());
                    long healthNanos=System.nanoTime()-start;assertEquals(200,health.statusCode());assertTrue(healthNanos<Duration.ofSeconds(2).toNanos());
                    System.out.println(new cn.dreamingfish.updater.protocol.JsonCodec().writeString(Map.of("probe","64-slow-default-profile","healthMillis",healthNanos/1_000_000.0,"metrics",server.metrics())));
                    var full=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v1/objects/sha256/"+hash)).timeout(Duration.ofSeconds(2)).build(),HttpResponse.BodyHandlers.ofString());
                    assertEquals(503,full.statusCode());assertEquals("2",full.headers().firstValue("Retry-After").orElseThrow());
                }
                assertTrue(server.metrics().activeDownloads()<=16);assertTrue(server.metrics().queuedControl()<=64);assertTrue(server.metrics().downloadThreads()<=16);assertTrue(server.metrics().rejected()>0);
            }finally{for(Socket socket:sockets)socket.close();}
        }
    }
    @Test void incompleteBodiesOnPublicReadEndpointsAreRejectedWithoutWaitingForEof()throws Exception {
        var f=new ManagementFixture(temporary.resolve("fixture"));
        try(var server=new PublicFileServer(f.database,f.objects,new InetSocketAddress("127.0.0.1",0));Socket socket=new Socket()){
            server.start();socket.connect(new InetSocketAddress("127.0.0.1",server.address().getPort()));socket.setSoTimeout(2000);
            socket.getOutputStream().write("GET /v1/objects/sha256/invalid HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1048576\r\n\r\npartial".getBytes());
            String status=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream())).readLine();assertTrue(status.contains("400"));
        }
    }
    @Test void stalledResponseIsClosedAndItsDownloadSlotIsReusable()throws Exception {
        var f=new ManagementFixture(temporary.resolve("fixture"));Path object=object(f);String hash=object.getFileName().toString();
        try(var server=new PublicFileServer(f.database,f.objects,new InetSocketAddress("127.0.0.1",0),new PublicFileServer.Limits(2,8,1,1))) {
            server.start();int port=server.address().getPort();
            try(Socket slow=new Socket()) {
                slow.setReceiveBufferSize(1024);slow.connect(new InetSocketAddress("127.0.0.1",port));
                slow.getOutputStream().write(("GET /v1/objects/sha256/"+hash+" HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes());
                long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
                while(server.metrics().stalled()==0&&System.nanoTime()<deadline)Thread.sleep(25);
                assertEquals(1,server.metrics().stalled());
                try(var client=HttpClient.newHttpClient()){
                    var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v1/objects/sha256/"+hash)).header("Range","bytes=0-9").timeout(Duration.ofSeconds(2)).build(),HttpResponse.BodyHandlers.ofByteArray());
                    assertEquals(206,response.statusCode());assertEquals(10,response.body().length);
                }
            }
        }
    }
}
