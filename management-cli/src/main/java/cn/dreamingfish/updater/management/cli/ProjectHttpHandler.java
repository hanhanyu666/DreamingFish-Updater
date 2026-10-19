package cn.dreamingfish.updater.management.cli;
import com.sun.net.httpserver.HttpExchange;
import java.util.*;
import static cn.dreamingfish.updater.management.cli.AdminWebServer.*;

final class ProjectHttpHandler {
    private final AdminWebServer io;
    ProjectHttpHandler(AdminWebServer io){this.io=io;}
    boolean handle(HttpExchange exchange,List<String> segments)throws Exception {
        if (segments.size() == 2 && segments.get(1).equals("projects")
                && exchange.getRequestMethod().equals("POST")) {
            ProjectRequest request = io.readJson(exchange, ProjectRequest.class);
            io.sendJson(exchange, 201, io.mutate(() -> io.createProject(request)));
            return true;
        }
        if(segments.size()<3||!segments.get(1).equals("projects"))return false;
        String projectId=segments.get(2);
        if (segments.size() == 3 && exchange.getRequestMethod().equals("GET")) {
            io.sendJson(exchange, 200,
                    io.projectDetails(projectId, query(exchange.getRequestURI(), "platform")));
            return true;
        }
        if (segments.size() == 3 && exchange.getRequestMethod().equals("PUT")) {
            ProjectRequest request = io.readJson(exchange, ProjectRequest.class);
            io.sendJson(exchange, 200, io.mutate(() -> io.configureProject(projectId, request)));
            return true;
        }
        return false;
    }
}
