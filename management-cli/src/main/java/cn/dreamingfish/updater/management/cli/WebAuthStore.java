package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ManagementException;
import cn.dreamingfish.updater.protocol.JsonCodec;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Persistent administrator credentials and bounded, in-memory browser sessions. */
final class WebAuthStore {
    private static final int CONFIG_SCHEMA = 1;
    static final int ITERATIONS = 600_000;
    private static final int MAX_ITERATIONS = 10_000_000;
    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    static final Duration ABSOLUTE_TIMEOUT = Duration.ofHours(12);
    private static final int MAX_SESSIONS = 8;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path file;
    private final JsonCodec json = new JsonCodec();
    private final Clock clock;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private volatile AuthConfig config;

    WebAuthStore(Path file) { this(file, Clock.systemUTC()); }
    WebAuthStore(Path file, Clock clock) {
        this.file = file.toAbsolutePath().normalize();
        this.clock = clock;
        if (Files.isRegularFile(this.file)) try {
            config = json.read(this.file, AuthConfig.class);
            validateConfig(config);
        } catch (IOException | RuntimeException e) {
            throw new ManagementException("无法读取 Web 账户配置: " + this.file, e);
        }
    }

    boolean registered() { return config != null; }
    boolean localBypass() { return config != null && config.allowLocalBypass; }
    String username() { return config == null ? "" : config.username; }

    synchronized void register(String username, char[] password, boolean localBypass) {
        if (registered()) throw new IllegalStateException("Web 管理账户已注册");
        validate(username, password);
        AuthConfig replacement = create(username.trim(), password, localBypass);
        save(replacement);
        config = replacement;
    }

    boolean verify(String username, char[] password) {
        AuthConfig current = config;
        if (current == null || username == null || password == null) return false;
        byte[] actual = derive(password, Base64.getDecoder().decode(current.salt), current.iterations);
        boolean matches = MessageDigest.isEqual(actual, Base64.getDecoder().decode(current.hash));
        return matches & MessageDigest.isEqual(username.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                current.username.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    synchronized void update(String currentPassword, String username, String newPassword, boolean localBypass) {
        AuthConfig old = config;
        if (old == null || !verify(old.username, chars(currentPassword))) throw new SecurityException("当前密码错误");
        String name = username == null || username.isBlank() ? old.username : username.trim();
        char[] replacement = newPassword == null || newPassword.isEmpty() ? null : chars(newPassword);
        if (replacement != null) validate(name, replacement); else validateUsername(name);
        AuthConfig updated = replacement == null
                ? new AuthConfig(CONFIG_SCHEMA, name, old.salt, old.hash,
                        old.iterations, localBypass)
                : create(name, replacement, localBypass);
        save(updated);
        config = updated;
        sessions.clear();
    }

    synchronized String createSession() {
        cleanup();
        while (sessions.size() >= MAX_SESSIONS) {
            sessions.entrySet().stream().min(Comparator.comparing(e -> e.getValue().lastUsed))
                    .ifPresent(e -> sessions.remove(e.getKey()));
        }
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant(); sessions.put(id, new Session(now, now)); return id;
    }

    boolean sessionValid(String id) {
        if (id == null) return false;
        Session session = sessions.get(id); Instant now = clock.instant();
        if (session == null || now.isAfter(session.created.plus(ABSOLUTE_TIMEOUT))
                || now.isAfter(session.lastUsed.plus(IDLE_TIMEOUT))) { sessions.remove(id); return false; }
        session.lastUsed = now; return true;
    }
    void logout(String id) { if (id != null) sessions.remove(id); }
    void cleanup() {
        Instant now = clock.instant();
        sessions.entrySet().removeIf(entry -> expired(entry.getValue(), now));
    }

    private static boolean expired(Session session, Instant now) {
        return now.isAfter(session.created.plus(ABSOLUTE_TIMEOUT))
                || now.isAfter(session.lastUsed.plus(IDLE_TIMEOUT));
    }

    private AuthConfig create(String username, char[] password, boolean bypass) {
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        return new AuthConfig(CONFIG_SCHEMA, username,
                Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS)), ITERATIONS, bypass);
    }
    private void save(AuthConfig value) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try {
                Files.write(tmp, json.writePretty(value));
                try { Files.setPosixFilePermissions(tmp, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
                catch (UnsupportedOperationException ignored) { }
                try { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(tmp); }
        } catch (IOException e) { throw new ManagementException("无法保存 Web 账户配置", e); }
    }
    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (Exception e) { throw new ManagementException("当前 Java 不支持安全密码哈希", e); }
        finally { spec.clearPassword(); Arrays.fill(password, '\0'); }
    }
    static void validate(String username, char[] password) {
        validateUsername(username);
        if (password == null || password.length < 10)
            throw new IllegalArgumentException("密码至少需要 10 位（建议使用 14 位以上的长密码）");
        if (password.length > 256)
            throw new IllegalArgumentException("密码不能超过 256 个字符");
    }
    private static void validateUsername(String username) {
        if (username == null || !username.trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{2,31}"))
            throw new IllegalArgumentException("用户名需为 3-32 位字母、数字、点、下划线或连字符");
    }
    private static char[] chars(String value) { return value == null ? new char[0] : value.toCharArray(); }
    private static void validateConfig(AuthConfig value) {
        if (value == null || value.schemaVersion != CONFIG_SCHEMA) {
            throw new IllegalArgumentException("不支持的账户配置版本");
        }
        validateUsername(value.username);
        if (value.iterations < ITERATIONS || value.iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException("账户密码哈希参数无效");
        }
        try {
            byte[] salt = Base64.getDecoder().decode(value.salt);
            byte[] hash = Base64.getDecoder().decode(value.hash);
            if (salt.length < 16 || hash.length != 32) {
                throw new IllegalArgumentException("账户密码哈希数据无效");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("账户密码哈希数据无效", e);
        }
    }
    private static final class Session { final Instant created; volatile Instant lastUsed; Session(Instant c, Instant l){created=c;lastUsed=l;} }
    static final class AuthConfig {
        public int schemaVersion; public String username; public String salt; public String hash; public int iterations; public boolean allowLocalBypass;
        public AuthConfig() { }
        AuthConfig(int s,String u,String salt,String hash,int i,boolean b){schemaVersion=s;username=u;this.salt=salt;this.hash=hash;iterations=i;allowLocalBypass=b;}
    }
}
