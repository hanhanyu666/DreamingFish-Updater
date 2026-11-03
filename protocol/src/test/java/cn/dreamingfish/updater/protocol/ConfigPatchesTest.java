package cn.dreamingfish.updater.protocol;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ConfigPatchesTest {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    @Test void jsonPreservesAccountsAndUnknownFieldsAndIsSemanticallyIdempotent() {
        byte[] patch = bytes("{\"schemaVersion\":1,\"format\":\"json\",\"set\":{\"theme\":\"blue\",\"appearance\":{\"background\":\"server.png\"}}}");
        byte[] original = bytes("{\"accounts\":[{\"accessToken\":\"local-only\"}],\"maxMemory\":6144,\"unknown\":true,\"appearance\":{\"playerChoice\":1}}");
        var result = new JsonCodec().read(ConfigPatches.apply("hmcl.json", patch, original), com.fasterxml.jackson.databind.JsonNode.class);
        assertEquals("local-only", result.path("accounts").get(0).path("accessToken").asText());
        assertEquals(6144, result.path("maxMemory").asInt()); assertTrue(result.path("unknown").asBoolean());
        assertEquals(1, result.path("appearance").path("playerChoice").asInt());
        assertTrue(ConfigPatches.satisfied("hmcl.json", patch, bytes(result.toString())));
        assertThrows(ProtocolException.class, () -> ConfigPatches.validate(bytes("{\"schemaVersion\":1,\"format\":\"json\",\"set\":{\"accounts\":[]}}")));
        assertThrows(ProtocolException.class, () -> ConfigPatches.apply("hmcl.json", patch, bytes("{broken")));
    }
    @Test void iniPreservesUtf16BomCommentsAndPersonalSettings() {
        String content = "; player comment\r\nLoginToken=local-only\r\nLaunchArgumentRam=6144\r\nUiCustomType=0\r\n";
        byte[] text = content.getBytes(StandardCharsets.UTF_16LE), original = new byte[text.length + 2];
        original[0]=(byte)0xff; original[1]=(byte)0xfe; System.arraycopy(text,0,original,2,text.length);
        byte[] patch=bytes("{\"schemaVersion\":1,\"format\":\"ini\",\"set\":{\"UiCustomType\":\"2\"}}");
        byte[] result=ConfigPatches.apply("PCL/Setup.ini",patch,original);
        assertEquals((byte)0xff,result[0]);
        String merged=new String(result,2,result.length-2,StandardCharsets.UTF_16LE);
        assertTrue(merged.contains("LoginToken=local-only")); assertTrue(merged.contains("LaunchArgumentRam=6144"));
        assertTrue(merged.contains("UiCustomType=2")); assertTrue(merged.contains("; player comment"));
        assertTrue(ConfigPatches.satisfied("PCL/Setup.ini",patch,result));
        assertThrows(ProtocolException.class,()->ConfigPatches.apply("Setup.ini",patch,bytes("UiCustomType=0\nUiCustomType=1")));
    }
    @Test void jvmKeepsConditionalArgumentsAndReplacesOnlyTheBootstrapAgent() {
        byte[] original=bytes("{\"id\":\"Demo\",\"arguments\":{\"jvm\":[\"-Xmx4G\",{\"rules\":[],\"value\":\"conditional\"},\"-javaagent:${game_directory}/.dreamingfish-bootstrap/bootstrap-agent.jar\",\"-javaagent:other.jar\"]},\"unknown\":7}");
        byte[] patch=bytes("{\"schemaVersion\":1,\"format\":\"minecraft-jvm\",\"addJvmArguments\":[\"-javaagent:.dreamingfish-bootstrap/bootstrap-agent.jar\",\"-javaagent:.dreamingfish-bootstrap/bootstrap-agent.jar\"]}");
        var result=new JsonCodec().read(ConfigPatches.apply("Demo.json",patch,original),com.fasterxml.jackson.databind.JsonNode.class);
        assertEquals(7,result.path("unknown").asInt()); assertEquals(4,result.path("arguments").path("jvm").size());
        assertEquals("-Xmx4G",result.path("arguments").path("jvm").get(0).asText());
        assertTrue(result.path("arguments").path("jvm").get(1).isObject());
        assertEquals("-javaagent:other.jar",result.path("arguments").path("jvm").get(2).asText());
        assertTrue(ConfigPatches.satisfied("Demo.json",patch,bytes(result.toString())));
        assertThrows(ProtocolException.class,()->ConfigPatches.apply("Demo.json",patch,bytes("{}")));
    }
    @Test void parentReplacementIniSectionInjectionAndPersonalJvmOverridesAreRejected() {
        byte[] original=bytes("{\"appearance\":{\"accounts\":[\"local\"],\"unknown\":1}}");
        byte[] overwriteParent=bytes("{\"schemaVersion\":1,\"format\":\"json\",\"set\":{\"appearance\":\"replacement\"}}");
        assertThrows(ProtocolException.class,()->ConfigPatches.apply("launcher.json",overwriteParent,original));
        byte[] injectedSection=bytes("{\"schemaVersion\":1,\"format\":\"ini\",\"section\":\"A\\nInjected=1\\nB\",\"set\":{\"Theme\":\"blue\"}}");
        assertThrows(ProtocolException.class,()->ConfigPatches.validate(injectedSection));
        for(String argument:new String[]{"-Xmx8G","-Xms4G","-XX:MaxRAMPercentage=90","-Djava.home=other"}) {
            byte[] rule=bytes("{\"schemaVersion\":1,\"format\":\"minecraft-jvm\",\"addJvmArguments\":[\""+argument+"\"]}");
            assertThrows(ProtocolException.class,()->ConfigPatches.validate(rule),argument);
        }
    }
    @Test void pclColonSeparatedSettingsKeepTheirDelimiterAndPersonalRam() {
        byte[] original=bytes("VersionRamCustom:6144\nUiCustomType:0\nLaunchFolderSelect:D:/MC\n");
        byte[] patch=bytes("{\"schemaVersion\":1,\"format\":\"ini\",\"set\":{\"UiCustomType\":\"2\",\"UiCustomNet\":\"https://example.test/theme\"}}");
        String merged=new String(ConfigPatches.apply("PCL/Setup.ini",patch,original),StandardCharsets.UTF_8);
        assertTrue(merged.contains("VersionRamCustom:6144"));
        assertTrue(merged.contains("LaunchFolderSelect:D:/MC"));
        assertTrue(merged.contains("UiCustomType:2"));
        assertTrue(merged.contains("UiCustomNet:https://example.test/theme"));
        assertFalse(merged.contains("UiCustomType="));
        assertTrue(ConfigPatches.satisfied("PCL/Setup.ini",patch,bytes(merged)));
        byte[] ram=bytes("{\"schemaVersion\":1,\"format\":\"ini\",\"set\":{\"VersionRamCustom\":\"8192\"}}");
        assertThrows(ProtocolException.class,()->ConfigPatches.validate(ram));
    }
}
