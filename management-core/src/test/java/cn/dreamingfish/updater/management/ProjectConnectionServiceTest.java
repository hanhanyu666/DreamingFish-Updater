package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.JsonCodec;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectConnectionServiceTest {
    @Test void reportsOnlyTheRequestedProjectsEndpointAndRejectsInvalidSchemes() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/",exchange->{
            String value=exchange.getRequestURI().getPath().contains("/demo/") ? "{\"projectId\":\"demo\"}" : "{\"projectId\":\"other\"}";
            byte[] body=value.getBytes(); exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);} finally {exchange.close();}
        });
        server.start();
        try {
            var service=new ProjectConnectionService(new JsonCodec()); String address="http://127.0.0.1:"+server.getAddress().getPort();
            assertTrue(service.check("demo",address).ok()); assertFalse(service.check("wrong",address).ok());
            assertThrows(ManagementException.class,()->service.check("demo","file:///etc/passwd"));
            assertThrows(ManagementException.class,()->service.check("demo","http://user:password@localhost/"));
        } finally {server.stop(0);}
    }
}
