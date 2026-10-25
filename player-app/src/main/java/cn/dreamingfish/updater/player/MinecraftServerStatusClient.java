package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.protocol.JsonCodec;
import com.fasterxml.jackson.databind.JsonNode;
import javax.naming.directory.InitialDirContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.IDN;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Bounded Java server-list ping, including DNS, SRV lookup, connect and response reads. */
final class MinecraftServerStatusClient {
    private static final int MAX_PACKET_BYTES = 1024 * 1024;
    private final Duration timeout;
    private final SrvResolver resolver;

    record Address(String host, int port, boolean explicitPort) { }
    record Srv(String host, int port, int priority, int weight) { }
    @FunctionalInterface interface SrvResolver { Srv resolve(String host) throws Exception; }

    MinecraftServerStatusClient() { this(Duration.ofSeconds(3), MinecraftServerStatusClient::lookupSrv); }
    MinecraftServerStatusClient(Duration timeout, SrvResolver resolver) { this.timeout = timeout; this.resolver = resolver; }

    ServerStatus query(String address) {
        FutureTask<ServerStatus> pending = new FutureTask<>(() -> request(parseAddress(address)));
        Thread.ofVirtual().name("minecraft-status-request").start(pending);
        try { return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); return ServerStatus.offline(); }
        catch (Exception error) { return ServerStatus.offline(); }
        finally { pending.cancel(true); }
    }

    private ServerStatus request(Address address) throws Exception {
        Srv endpoint = null;
        if (!address.explicitPort() && !address.host().contains(":") && !address.host().matches("[0-9.]+")) {
            try { endpoint = resolver.resolve(address.host()); } catch (Exception ignored) { }
        }
        String host = endpoint == null ? address.host() : endpoint.host();
        int port = endpoint == null ? address.port() : endpoint.port();
        try (Socket socket = new Socket()) {
            int millis = (int) Math.max(1, Math.min(Integer.MAX_VALUE, timeout.toMillis()));
            socket.connect(new InetSocketAddress(host, port), millis);
            socket.setSoTimeout(millis);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream handshake = new DataOutputStream(bytes);
            writeVarInt(handshake, 0); // Handshake packet ID.
            writeVarInt(handshake, -1); // Status accepts a protocol-independent query.
            byte[] hostname = address.host().getBytes(StandardCharsets.UTF_8);
            writeVarInt(handshake, hostname.length);
            handshake.write(hostname);
            handshake.writeShort(port);
            writeVarInt(handshake, 1); // Next state: status.
            packet(output, bytes.toByteArray());
            long sentAt = System.nanoTime();
            packet(output, new byte[]{0});
            DataInputStream input = new DataInputStream(socket.getInputStream());
            byte[] response = readPacket(input);
            long latency = Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sentAt));
            DataInputStream status = new DataInputStream(new ByteArrayInputStream(response));
            if (readVarInt(status) != 0) throw new IOException("Unexpected status packet");
            int length = readVarInt(status);
            if (length < 0 || length > MAX_PACKET_BYTES || length != status.available()) throw new IOException("Invalid status JSON length");
            JsonNode json = new JsonCodec().read(status.readNBytes(length), JsonNode.class);
            if (!json.isObject() || !json.path("players").isObject() || !json.path("version").isObject()) throw new IOException("Invalid status response");
            Integer online = nonNegative(json.path("players").get("online"));
            Integer max = nonNegative(json.path("players").get("max"));
            if (online == null || max == null) throw new IOException("Invalid player counts");
            return new ServerStatus("ONLINE", online, max, latency, legacyMotd(json.get("description")),
                    limitedText(json.path("version").get("name"), 256), favicon(json.get("favicon")), Instant.now());
        }
    }

    static Address parseAddress(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Empty server address");
        String value = raw.trim();
        if (value.length() > 255 || value.chars().anyMatch(c -> Character.isWhitespace(c) || c < 32)
                || value.contains("/") || value.contains("@") || value.contains("\\")) throw new IllegalArgumentException("Invalid server address");
        String host;
        String port = null;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 2) throw new IllegalArgumentException("Invalid IPv6 address");
            host = value.substring(1, end);
            String tail = value.substring(end + 1);
            if (!tail.isEmpty()) {
                if (!tail.startsWith(":")) throw new IllegalArgumentException("Invalid port");
                port = tail.substring(1);
            }
        } else if (value.indexOf(':') == value.lastIndexOf(':') && value.contains(":")) {
            host = value.substring(0, value.indexOf(':'));
            port = value.substring(value.indexOf(':') + 1);
        } else host = value;
        if (host.isEmpty()) throw new IllegalArgumentException("Missing host");
        if (!host.contains(":")) host = IDN.toASCII(host);
        int number = port == null ? 25565 : Integer.parseInt(port);
        if (number < 1 || number > 65535) throw new IllegalArgumentException("Invalid port");
        return new Address(host, number, port != null);
    }

    private static Srv lookupSrv(String host) throws Exception {
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        environment.put("com.sun.jndi.dns.timeout.initial", "1000");
        environment.put("com.sun.jndi.dns.timeout.retries", "1");
        InitialDirContext context = new InitialDirContext(environment);
        try {
            var records = context.getAttributes("_minecraft._tcp." + host, new String[]{"SRV"}).get("SRV");
            if (records == null) return null;
            ArrayList<Srv> options = new ArrayList<>();
            for (int index = 0; index < records.size(); index++) {
                String[] values = records.get(index).toString().trim().split("\\s+");
                if (values.length != 4 || values[3].equals(".")) continue;
                int port = Integer.parseInt(values[2]);
                if (port < 1 || port > 65535) continue;
                String target = values[3].replaceFirst("\\.$", "");
                options.add(new Srv(IDN.toASCII(target), port, Integer.parseInt(values[0]), Integer.parseInt(values[1])));
            }
            return options.stream().min(Comparator.comparingInt(Srv::priority).thenComparing(Comparator.comparingInt(Srv::weight).reversed())).orElse(null);
        } finally { context.close(); }
    }

    static String legacyMotd(JsonNode description) {
        StringBuilder result = new StringBuilder();
        appendComponent(description, Style.EMPTY, new Style[]{Style.EMPTY}, result, 0);
        return result.length() > 16_384 ? result.substring(0, 16_384) : result.toString();
    }

    private record Style(String color, boolean bold, boolean italic, boolean underlined, boolean strike, boolean obfuscated) {
        static final Style EMPTY = new Style("", false, false, false, false, false);
        String codes() { return color + (obfuscated ? "§k" : "") + (bold ? "§l" : "") + (strike ? "§m" : "") + (underlined ? "§n" : "") + (italic ? "§o" : ""); }
    }

    private static void appendComponent(JsonNode node, Style inherited, Style[] current, StringBuilder output, int depth) {
        if (node == null || node.isNull() || depth > 64 || output.length() >= 16_384) return;
        if (node.isArray()) { for (JsonNode child : node) appendComponent(child, inherited, current, output, depth + 1); return; }
        Style style = inherited;
        if (node.isObject()) {
            if ("reset".equals(node.path("color").asText())) inherited = Style.EMPTY;
            String color = node.has("color") ? colorCode(node.path("color").asText()) : inherited.color();
            style = new Style(color, node.path("bold").asBoolean(inherited.bold()), node.path("italic").asBoolean(inherited.italic()),
                    node.path("underlined").asBoolean(inherited.underlined()), node.path("strikethrough").asBoolean(inherited.strike()),
                    node.path("obfuscated").asBoolean(inherited.obfuscated()));
        }
        String text = node.isValueNode() ? node.asText() : node.path("text").asText("");
        if (text.isEmpty() && node.has("translate")) text = node.path("fallback").asText(node.path("translate").asText());
        if (!text.isEmpty()) {
            if (!style.equals(current[0])) {
                if (!output.isEmpty()) output.append("§r");
                output.append(style.codes());
            }
            current[0] = style;
            output.append(text, 0, Math.min(text.length(), Math.max(0, 16_384 - output.length())));
        }
        if (node.isObject()) appendComponent(node.get("extra"), style, current, output, depth + 1);
    }

    private static final Map<String, String> COLORS = Map.ofEntries(
            Map.entry("black", "0"), Map.entry("dark_blue", "1"), Map.entry("dark_green", "2"), Map.entry("dark_aqua", "3"),
            Map.entry("dark_red", "4"), Map.entry("dark_purple", "5"), Map.entry("gold", "6"), Map.entry("gray", "7"),
            Map.entry("dark_gray", "8"), Map.entry("blue", "9"), Map.entry("green", "a"), Map.entry("aqua", "b"),
            Map.entry("red", "c"), Map.entry("light_purple", "d"), Map.entry("yellow", "e"), Map.entry("white", "f"));

    private static String colorCode(String color) {
        if (color.matches("#[0-9a-fA-F]{6}")) {
            StringBuilder code = new StringBuilder("§x");
            for (char character : color.substring(1).toCharArray()) code.append('§').append(character);
            return code.toString();
        }
        return COLORS.containsKey(color) ? "§" + COLORS.get(color) : "";
    }

    private static String favicon(JsonNode node) {
        String value = limitedText(node, 180_000);
        if (value == null || !value.startsWith("data:image/png;base64,")) return null;
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(value.substring("data:image/png;base64,".length()));
            return bytes.length <= 128 * 1024 && bytes.length >= 8 && bytes[0] == (byte) 137 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G' ? value : null;
        } catch (IllegalArgumentException error) { return null; }
    }

    private static Integer nonNegative(JsonNode node) { return node != null && node.isIntegralNumber() && node.canConvertToInt() && node.intValue() >= 0 ? node.intValue() : null; }
    private static String limitedText(JsonNode node, int limit) { return node != null && node.isTextual() && node.textValue().length() <= limit ? node.textValue() : null; }

    static int readVarInt(DataInputStream input) throws IOException {
        int result = 0;
        for (int position = 0; position < 5; position++) {
            int value = input.readUnsignedByte();
            if (position == 4 && (value & 0xf0) != 0) throw new IOException("VarInt overflow");
            result |= (value & 0x7f) << (7 * position);
            if ((value & 0x80) == 0) return result;
        }
        throw new IOException("VarInt is too long");
    }

    static void writeVarInt(DataOutputStream output, int value) throws IOException {
        while ((value & ~0x7f) != 0) { output.writeByte((value & 0x7f) | 0x80); value >>>= 7; }
        output.writeByte(value);
    }

    private static void packet(DataOutputStream output, byte[] bytes) throws IOException { writeVarInt(output, bytes.length); output.write(bytes); output.flush(); }
    private static byte[] readPacket(DataInputStream input) throws IOException {
        int size = readVarInt(input);
        if (size < 1 || size > MAX_PACKET_BYTES) throw new IOException("Invalid packet length");
        byte[] bytes = input.readNBytes(size);
        if (bytes.length != size) throw new IOException("Truncated packet");
        return bytes;
    }
}
