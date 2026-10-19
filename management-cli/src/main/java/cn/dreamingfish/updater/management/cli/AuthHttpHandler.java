package cn.dreamingfish.updater.management.cli;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.*;
import static cn.dreamingfish.updater.management.cli.AdminWebServer.*;

/** Authentication adapter; it cannot submit application jobs or persist request credentials. */
final class AuthHttpHandler {
    private final AdminWebServer io;private final WebAuthStore auth;
    private final Map<String,LoginAttempts> loginAttempts=new java.util.concurrent.ConcurrentHashMap<>();
    AuthHttpHandler(AdminWebServer io,WebAuthStore auth){this.io=io;this.auth=auth;}
    void handle(HttpExchange exchange, String path) throws IOException {
        boolean local = io.isLocalClient(exchange);
        if (path.equals("/api/auth/status") && exchange.getRequestMethod().equals("GET")) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            io.sendJson(exchange, 200, Map.of("registered", auth.registered(), "authenticated",
                    io.authenticated(exchange), "local", local, "localBypass", auth.localBypass(),
                    "username", io.authenticated(exchange) ? auth.username() : ""));
            return;
        }
        if (path.equals("/api/auth/register") && exchange.getRequestMethod().equals("POST")) {
            if (!local) throw new WebApiException(403, "local_only", "首次注册只能从管理端本机访问；远程服务器请先使用 SSH 隧道");
            if (auth.registered()) {
                throw new WebApiException(409, "already_registered",
                        "Web 管理账户已经注册");
            }
            AuthRequest request = io.readJson(exchange, AuthRequest.class);
            if (!Objects.equals(request.password, request.confirmPassword))
                throw new WebApiException(400, "password_mismatch", "两次输入的密码不一致");
            auth.register(request.username, request.password == null ? new char[0] : request.password.toCharArray(),
                    Boolean.TRUE.equals(request.allowLocalBypass));
            io.setSessionCookie(exchange, auth.createSession(), io.secureRequest(exchange));
            io.clearLoggedOutCookie(exchange);
            io.sendJson(exchange, 201, Map.of("authenticated", true)); return;
        }
        if (path.equals("/api/auth/login") && exchange.getRequestMethod().equals("POST")) {
            if (!local && !io.secureRequest(exchange))
                throw new WebApiException(400, "https_required", "远程登录必须使用 HTTPS，请配置 Caddy/Nginx TLS 反向代理");
            AuthRequest request = io.readJson(exchange, AuthRequest.class);
            String ip = io.clientAddress(exchange);
            LoginAttempts attempts = loginAttempts.computeIfAbsent(
                    ip, ignored -> new LoginAttempts());
            trimAttempts();
            if (attempts.blocked()) throw new WebApiException(429, "login_limited", "登录失败次数过多，请稍后再试");
            if (!auth.verify(request.username, request.password == null ? new char[0] : request.password.toCharArray())) {
                attempts.fail();
                throw new WebApiException(401, "invalid_credentials", "用户名或密码错误");
            }
            loginAttempts.remove(ip); io.setSessionCookie(exchange, auth.createSession(), io.secureRequest(exchange));
            io.clearLoggedOutCookie(exchange);
            io.sendJson(exchange, 200, Map.of("authenticated", true)); return;
        }
        if (path.equals("/api/auth/logout") && exchange.getRequestMethod().equals("POST")) {
            auth.logout(cookie(exchange, SESSION_COOKIE));
            io.clearSessionCookie(exchange);
            io.setLoggedOutCookie(exchange);
            io.sendJson(exchange, 200, Map.of("authenticated", false)); return;
        }
        if (path.equals("/api/auth/account") && exchange.getRequestMethod().equals("PUT")) {
            io.requireAuthentication(exchange); io.requireToken(exchange); AuthRequest request = io.readJson(exchange, AuthRequest.class);
            if (request.newPassword != null && !Objects.equals(request.newPassword, request.confirmPassword))
                throw new WebApiException(400, "password_mismatch", "两次输入的新密码不一致");
            try { auth.update(request.password, request.username, request.newPassword, Boolean.TRUE.equals(request.allowLocalBypass)); }
            catch (SecurityException e) { throw new WebApiException(401, "invalid_credentials", "当前密码错误"); }
            io.setSessionCookie(exchange, auth.createSession(), io.secureRequest(exchange));
            io.sendJson(exchange, 200, Map.of("updated", true)); return;
        }
        throw new WebApiException(404, "not_found", "认证 API 不存在");
    }

    private void trimAttempts() {
        if (loginAttempts.size() > 1024) loginAttempts.entrySet().removeIf(e -> e.getValue().stale());
        while (loginAttempts.size() > 1024) {
            String candidate = loginAttempts.keySet().stream()
                    .findFirst().orElse(null);
            if (candidate == null) break;
            loginAttempts.remove(candidate);
        }
    }
    private record AuthRequest(String username, String password, String confirmPassword,
                               String newPassword, Boolean allowLocalBypass) { }
    private static final class LoginAttempts {
        private int failures; private long first = System.currentTimeMillis();
        synchronized void fail() { if (stale()) { failures = 0; first = System.currentTimeMillis(); } failures++; }
        synchronized boolean blocked() { return !stale() && failures >= 5; }
        synchronized boolean stale() { return System.currentTimeMillis() - first > 5 * 60_000L; }
    }
}
