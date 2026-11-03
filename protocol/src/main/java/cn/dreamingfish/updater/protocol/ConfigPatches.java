package cn.dreamingfish.updater.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Small declarative patches preserve fields the owner did not explicitly select. */
public final class ConfigPatches {
    public static final String SUFFIX = ".dfs-patch.json";
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final JsonCodec JSON = new JsonCodec();
    private ConfigPatches() {}
    public static boolean isPatch(String path) { return ManagedPaths.fold(path).endsWith(SUFFIX); }
    public static String targetPath(String path) {
        return isPatch(path) ? PathSafety.normalizeManifestPath(path.substring(0, path.length() - SUFFIX.length())) : path;
    }
    public static void validate(byte[] bytes) {
        if (bytes.length > 256 * 1024) throw new ProtocolException("配置合并规则最多 256 KiB");
        JsonNode patch = JSON.read(bytes, JsonNode.class);
        if (!patch.isObject() || patch.path("schemaVersion").asInt() != 1
                || !Set.of("json", "ini", "minecraft-jvm").contains(patch.path("format").asText()))
            throw new ProtocolException("配置合并规则需要 schemaVersion 1 和 json、ini 或 minecraft-jvm 格式");
        Set<String> allowed = patch.path("format").asText().equals("minecraft-jvm")
                ? Set.of("schemaVersion", "format", "addJvmArguments", "removeJvmArguments")
                : Set.of("schemaVersion", "format", "set", "section");
        patch.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw new ProtocolException("未知的配置合并规则字段：" + key); });
        if (patch.path("format").asText().equals("minecraft-jvm")) {
            validateArguments(patch.path("addJvmArguments")); validateArguments(patch.path("removeJvmArguments"));
        } else {
            if (!patch.path("set").isObject() || patch.path("set").isEmpty()) throw new ProtocolException("set 必须包含要修改的配置字段");
            validateFields(patch.path("set"), 0);
            if (patch.has("section") && (!patch.get("section").isTextual()
                    || patch.get("section").asText().chars().anyMatch(c -> c=='\r' || c=='\n' || c=='[' || c==']')))
                throw new ProtocolException("INI 节名称无效");
            if (patch.path("format").asText().equals("ini")) patch.path("set").fields().forEachRemaining(entry -> {
                if (!entry.getKey().matches("[A-Za-z0-9_.-]{1,128}") || !entry.getValue().isValueNode()
                        || entry.getValue().isNull() || entry.getValue().asText().matches("(?s).*[\\r\\n].*"))
                    throw new ProtocolException("INI 合并只允许单行键和值");
            });
        }
    }
    private static void validateArguments(JsonNode arguments) {
        if (arguments.isMissingNode()) return;
        if (!arguments.isArray() || arguments.size() > 64) throw new ProtocolException("JVM 参数必须是最多 64 项的字符串数组");
        for (var argument : arguments) {
            if (!argument.isTextual() || argument.asText().isBlank()
                    || argument.asText().length() > 2048 || argument.asText().chars().anyMatch(Character::isISOControl))
                throw new ProtocolException("JVM 参数必须是非空的单项字符串");
            String value=argument.asText().toLowerCase(Locale.ROOT);
            if (value.startsWith("-xmx") || value.startsWith("-xms") || value.startsWith("-xmn")
                    || value.matches("-xx:[+-]?(maxram|minram|initialram|maxheap|initialheap|newsize|maxnewsize).*" )
                    || value.startsWith("-djava.home=") || value.startsWith("-duser.language=") || value.startsWith("-duser.country="))
                throw new ProtocolException("JVM 合并不能改写玩家个人内存、Java 位置或语言设置");
        }
    }
    private static void validateFields(JsonNode value, int depth) {
        if (depth > 16) throw new ProtocolException("配置合并字段嵌套过深");
        if (value.isObject()) value.fields().forEachRemaining(entry -> {
            String key = entry.getKey().replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
            if (entry.getKey().isBlank() || entry.getKey().length() > 128 || entry.getKey().chars().anyMatch(Character::isISOControl)
                    || key.matches(".*(account|password|token|credential|login|auth|username|uuid).*" )
                    || key.contains("argumentram") || key.startsWith("versionram") || key.startsWith("launchram") || key.contains("memory")
                    || key.matches(".*(javapath|javadir|javalocation|gamedir|resolution|windowwidth|windowheight|language|locale).*")
                    || Set.of("proto", "constructor", "prototype", "java", "javapath", "javadirectory", "javalocation", "jvm", "memory", "maxmemory", "minmemory", "ram",
                    "maxram", "minram", "permsize", "language", "locale", "width", "height", "resolution").contains(key))
                throw new ProtocolException("配置合并不能改写玩家账户、凭据或受保护的个人设置：" + entry.getKey());
            validateFields(entry.getValue(), depth + 1);
        });
        else if (value.isArray()) { if (value.size() > 1024) throw new ProtocolException("配置合并数组过大"); for (var item : value) validateFields(item, depth + 1); }
    }

    public static byte[] apply(String target, byte[] patchBytes, byte[] original) {
        validate(patchBytes);
        if (original.length > MAX_BYTES) throw new ProtocolException("启动器配置超过 4 MiB，请保留原文件后手动核对");
        var patch = JSON.read(patchBytes, JsonNode.class); String format = patch.path("format").asText();
        Encoded text = decode(original);
        String result;
        if (format.equals("ini")) {
            if (!target.toLowerCase(Locale.ROOT).endsWith(".ini")) throw new ProtocolException("INI 合并目标必须是 .ini 文件");
            result = ini(text.text(), patch);
        } else {
            if (!target.toLowerCase(Locale.ROOT).endsWith(".json") && !target.toLowerCase(Locale.ROOT).endsWith(".cfg"))
                throw new ProtocolException("JSON 合并目标必须是 .json 或 JSON 格式的 .cfg 文件");
            JsonNode existing = text.text().isBlank() ? JSON.read("{}".getBytes(StandardCharsets.UTF_8), JsonNode.class)
                    : JSON.read(text.text().getBytes(StandardCharsets.UTF_8), JsonNode.class);
            if (!(existing instanceof ObjectNode object)) throw new ProtocolException("启动器配置必须是 JSON 对象，原文件未修改");
            if (format.equals("minecraft-jvm")) jvm(object, patch); else merge(object, patch.path("set"));
            result = new String(JSON.writePretty(object), StandardCharsets.UTF_8) + "\n";
        }
        byte[] body = result.getBytes(text.charset());
        if (body.length > MAX_BYTES) throw new ProtocolException("合并后的配置超过 4 MiB");
        byte[] bytes = Arrays.copyOf(text.bom(), text.bom().length + body.length);
        System.arraycopy(body, 0, bytes, text.bom().length, body.length); return bytes;
    }
    public static boolean satisfied(String target, byte[] patch, byte[] original) {
        byte[] applied = apply(target, patch, original);
        if (JSON.read(patch, JsonNode.class).path("format").asText().equals("ini")) return Arrays.equals(applied, original);
        if (original.length == 0) return false;
        return JSON.read(decode(applied).text().getBytes(StandardCharsets.UTF_8), JsonNode.class)
                .equals(JSON.read(decode(original).text().getBytes(StandardCharsets.UTF_8), JsonNode.class));
    }

    private static void merge(ObjectNode target, JsonNode values) {
        values.fields().forEachRemaining(entry -> {
            JsonNode old=target.get(entry.getKey()), next=entry.getValue();
            if (old!=null && !old.isNull() && old.getNodeType()!=next.getNodeType()
                    && !(old.isNumber() && next.isNumber()))
                throw new ProtocolException("配置字段类型不一致：" + entry.getKey());
            if (entry.getValue().isObject()) {
                ObjectNode child = old == null || old.isNull() ? target.putObject(entry.getKey()) : (ObjectNode) old;
                merge(child, entry.getValue());
            } else target.set(entry.getKey(), entry.getValue().deepCopy());
        });
    }
    private static void jvm(ObjectNode version, JsonNode patch) {
        if (!version.path("id").isTextual() || !version.path("arguments").isObject()
                || !version.path("arguments").path("jvm").isArray()) throw new ProtocolException("版本 JSON 没有可识别的 arguments.jvm，原文件未修改");
        List<JsonNode> previous = new ArrayList<>();
        version.path("arguments").path("jvm").forEach(value -> previous.add(value.deepCopy()));
        Set<String> remove = new HashSet<>();
        if (patch.path("removeJvmArguments").isArray()) patch.path("removeJvmArguments").forEach(value -> remove.add(value.asText()));
        List<String> additions = new ArrayList<>();
        if (patch.path("addJvmArguments").isArray()) patch.path("addJvmArguments").forEach(value -> { if (!additions.contains(value.asText())) additions.add(value.asText()); });
        boolean replaceBootstrap = additions.stream().anyMatch(ConfigPatches::bootstrapArgument);
        var arguments = ((ObjectNode) version.get("arguments")).putArray("jvm");
        Set<String> present = new HashSet<>();
        for (var value : previous) {
            if (value.isTextual() && (remove.contains(value.asText()) || replaceBootstrap && bootstrapArgument(value.asText()))) continue;
            arguments.add(value); if (value.isTextual()) present.add(value.asText());
        }
        for (String argument : additions) if (present.add(argument)) arguments.add(argument);
    }
    private static boolean bootstrapArgument(String argument) {
        return argument.startsWith("-javaagent:") && argument.replace('\\', '/').toLowerCase(Locale.ROOT).contains(".dreamingfish-bootstrap/bootstrap-agent.jar");
    }

    private static String ini(String original, JsonNode patch) {
        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(Arrays.asList(original.split("\\r?\\n", -1)));
        String section = patch.path("section").asText(""); String current = "";
        Map<String, String> desired = new LinkedHashMap<>();
        patch.path("set").fields().forEachRemaining(e -> desired.put(e.getKey(), e.getValue().asText()));
        Set<String> written = new HashSet<>(); int insertion = section.isEmpty() ? 0 : -1;
        char separator='='; boolean foundSeparator=false;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                current = trimmed.substring(1, trimmed.length() - 1);
                if (current.equalsIgnoreCase(section)) insertion = i + 1;
                continue;
            }
            if (!current.equalsIgnoreCase(section)) continue;
            insertion = i + 1;
            if (trimmed.startsWith(";") || trimmed.startsWith("#")) continue;
            int equal = keySeparator(trimmed); if (equal < 1) continue;
            if(!foundSeparator){separator=trimmed.charAt(equal);foundSeparator=true;}
            String key = trimmed.substring(0, equal).trim();
            for (var entry : desired.entrySet()) if (entry.getKey().equalsIgnoreCase(key)) {
                if (!written.add(entry.getKey().toLowerCase(Locale.ROOT))) throw new ProtocolException("INI 有重复的目标键，原文件未修改：" + key);
                String line=lines.get(i);int split=keySeparator(line);
                lines.set(i, line.substring(0,split+1) + entry.getValue());
            }
        }
        List<String> missing = new ArrayList<>();
        for (var entry : desired.entrySet()) if (!written.contains(entry.getKey().toLowerCase(Locale.ROOT))) missing.add(entry.getKey() + separator + entry.getValue());
        if (insertion < 0) { lines.add("[" + section + "]"); insertion = lines.size(); }
        lines.addAll(insertion, missing); return String.join(newline, lines);
    }
    private static int keySeparator(String line) {
        int equals=line.indexOf('='),colon=line.indexOf(':');
        return equals<0?colon:colon<0?equals:Math.min(equals,colon);
    }
    private record Encoded(String text, Charset charset, byte[] bom) {}
    private static Encoded decode(byte[] bytes) {
        Charset charset = StandardCharsets.UTF_8; int skip = 0;
        if (bytes.length >= 3 && bytes[0] == (byte)0xef && bytes[1] == (byte)0xbb && bytes[2] == (byte)0xbf) skip = 3;
        if (bytes.length >= 2 && bytes[0] == (byte)0xff && bytes[1] == (byte)0xfe) { charset = StandardCharsets.UTF_16LE; skip = 2; }
        try { return new Encoded(charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, skip, bytes.length - skip)).toString(), charset, Arrays.copyOf(bytes, skip)); }
        catch (CharacterCodingException error) { throw new ProtocolException("配置编码无法识别，只支持 UTF-8 和带 BOM 的 UTF-16LE，原文件未修改", error); }
    }
}
