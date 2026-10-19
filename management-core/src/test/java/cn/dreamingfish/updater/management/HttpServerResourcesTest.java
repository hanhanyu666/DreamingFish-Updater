package cn.dreamingfish.updater.management;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class HttpServerResourcesTest {
    private HttpServer server() throws Exception {
        HttpServerResources.configure();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try {
                boolean echo=exchange.getRequestURI().getPath().equals("/echo");
                byte[] body=echo?exchange.getRequestBody().readAllBytes():new byte[]{'x'};
                exchange.sendResponseHeaders(echo?200:403,body.length);
                exchange.getResponseBody().write(body);
            } finally {exchange.close();}
        }).getFilters().add(HttpServerResources.connectionLifecycle());
        server.start();return server;
    }
    private String response(Socket socket,String request) throws Exception {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        var bytes=new ByteArrayOutputStream();
        while(!bytes.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
            int value=socket.getInputStream().read();assertNotEquals(-1,value,"connection closed before response");bytes.write(value);
        }
        String headers=bytes.toString(StandardCharsets.US_ASCII);
        var length=java.util.regex.Pattern.compile("(?im)^Content-length: (\\d+)").matcher(headers);
        assertTrue(length.find());int size=Integer.parseInt(length.group(1));
        assertEquals(size,socket.getInputStream().readNBytes(size).length);
        return headers.toLowerCase(java.util.Locale.ROOT);
    }
    @Test void fullyReadFixedAndChunkedBodiesAllowTheNextRequestOnTheSameSocket() throws Exception {
        var server=server();
        try(var socket=new Socket("127.0.0.1",server.getAddress().getPort())) {
            socket.setSoTimeout(2000);
            for(String framing:new String[]{"Content-Length: 3\r\n\r\nabc","Transfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n"}) {
                String headers=response(socket,"POST /echo HTTP/1.1\r\nHost: localhost\r\n"+framing);
                assertTrue(headers.startsWith("http/1.1 200"));assertFalse(headers.contains("connection: close"));
            }
        } finally {server.stop(0);}
    }
    @Test void rejectedIncompleteUploadReturnsWithoutWaitingForTheRestOfItsBody() throws Exception {
        var server=server();
        try(var socket=new Socket("127.0.0.1",server.getAddress().getPort())) {
            socket.setSoTimeout(2000);
            String headers=response(socket,"PUT /reject HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1048576\r\n\r\npartial");
            assertTrue(headers.startsWith("http/1.1 403"));assertTrue(headers.contains("connection: close"));
        } finally {server.stop(0);}
    }
    @Test void explicitClientCloseIsHonoredAfterAnEmptyBodyReachesEof() throws Exception {
        var server=server();
        try(var socket=new Socket("127.0.0.1",server.getAddress().getPort())) {
            socket.setSoTimeout(2000);
            assertTrue(response(socket,"GET /reject HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").contains("connection: close"));
            assertEquals(-1,socket.getInputStream().read());
        } finally {server.stop(0);}
    }
}
