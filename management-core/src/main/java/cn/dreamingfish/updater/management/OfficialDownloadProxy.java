package cn.dreamingfish.updater.management;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Official GitHub downloads follow the proxy an owner already set up for other tools: HTTPS_PROXY (or
 * https_proxy) first, then the Windows system proxy. The JDK client only tunnels through plain HTTP proxies
 * without credentials, so other settings keep the direct route. Loopback and NO_PROXY hosts always stay direct.
 * Only this client uses it; uploads to the owner's own storage keep their existing route.
 */
final class OfficialDownloadProxy extends ProxySelector {
    private final Map<String, String> environment;
    private final Supplier<String> systemSetting;
    private volatile Proxy chosen;

    OfficialDownloadProxy(Map<String, String> environment, Supplier<String> systemSetting) {
        this.environment = environment; this.systemSetting = systemSetting;
    }

    static OfficialDownloadProxy current() {
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        return new OfficialDownloadProxy(System.getenv(), windows ? OfficialDownloadProxy::windowsSetting : () -> null);
    }

    @Override public List<Proxy> select(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return List.of(bypassed(host) ? Proxy.NO_PROXY : proxy());
    }

    @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }

    /** Settings are read on the first GitHub request, so creating the service never starts a process. */
    Proxy proxy() {
        Proxy current = chosen;
        if (current == null) {
            InetSocketAddress address = parse(variable("HTTPS_PROXY", "https_proxy"));
            if (address == null) {
                String setting = systemSetting.get();
                if (setting != null) address = windows(setting);
            }
            chosen = current = address == null ? Proxy.NO_PROXY : new Proxy(Proxy.Type.HTTP, address);
        }
        return current;
    }

    private boolean bypassed(String host) {
        if (host.equals("localhost") || host.startsWith("127.") || host.equals("[::1]") || host.equals("::1")) return true;
        for (String entry : Objects.requireNonNullElse(variable("NO_PROXY", "no_proxy"), "").split(",")) {
            String rule = entry.trim().toLowerCase(Locale.ROOT);
            if (rule.startsWith("*.")) rule = rule.substring(1);
            if (rule.equals("*")) return true;
            if (rule.isEmpty()) continue;
            String domain = rule.startsWith(".") ? rule.substring(1) : rule;
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        return false;
    }

    private String variable(String upper, String lower) {
        String value = environment.get(upper);
        return value == null || value.isBlank() ? environment.get(lower) : value;
    }

    /** "host:port" or "http://host:port"; credentials or another protocol cannot be used by the JDK client. */
    static InetSocketAddress parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String text = value.trim();
            URI uri = new URI(text.contains("://") ? text : "http://" + text);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getHost() == null) return null;
            return new InetSocketAddress(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 80);
        } catch (URISyntaxException | IllegalArgumentException invalid) { return null; }
    }

    /** Windows stores either "host:port" or per protocol, "http=host:port;https=host:port;socks=host:port". */
    static InetSocketAddress windows(String server) {
        if (!server.contains("=")) return parse(server);
        Map<String, String> byProtocol = new HashMap<>();
        for (String part : server.split(";")) {
            int equals = part.indexOf('=');
            if (equals > 0) byProtocol.put(part.substring(0, equals).trim().toLowerCase(Locale.ROOT), part.substring(equals + 1).trim());
        }
        return parse(byProtocol.getOrDefault("https", byProtocol.get("http")));
    }

    /** The enabled system proxy of the account running the service, or {@code null}. */
    private static String windowsSetting() {
        try {
            Process reg = new ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings")
                    .redirectErrorStream(true).start();
            String output = new String(reg.getInputStream().readNBytes(256 * 1024), StandardCharsets.ISO_8859_1);
            if (!reg.waitFor(5, TimeUnit.SECONDS)) { reg.destroyForcibly(); return null; }
            boolean enabled = false; String server = null;
            for (String line : output.split("\\R")) {
                String[] cells = line.trim().split("\\s+", 3);
                if (cells.length < 3) continue;
                if (cells[0].equals("ProxyEnable")) enabled = cells[2].trim().equals("0x1");
                if (cells[0].equals("ProxyServer")) server = cells[2].trim();
            }
            return enabled ? server : null;
        } catch (IOException unavailable) { return null;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return null; }
    }
}
