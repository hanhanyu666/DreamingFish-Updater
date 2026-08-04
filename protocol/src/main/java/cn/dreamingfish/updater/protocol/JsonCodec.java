package cn.dreamingfish.updater.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class JsonCodec {
    private static final int MAX_JSON_BYTES = 32 * 1024 * 1024;

    private final ObjectMapper mapper;

    public JsonCodec() {
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxStringLength(MAX_JSON_BYTES)
                        .maxDocumentLength(MAX_JSON_BYTES)
                        .build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        mapper = new ObjectMapper(factory)
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public byte[] write(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new ProtocolException("Unable to encode JSON", e);
        }
    }

    /** Deterministic, human-readable JSON for persisted .json files. */
    public byte[] writePretty(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        } catch (IOException e) {
            throw new ProtocolException("Unable to encode JSON", e);
        }
    }

    public String writeString(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new ProtocolException("Unable to encode JSON", e);
        }
    }

    public void write(Path target, Object value) throws IOException {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Files.write(target, writePretty(value));
    }

    public <T> T read(byte[] bytes, Class<T> type) {
        if (bytes.length > MAX_JSON_BYTES) {
            throw new ProtocolException("JSON document exceeds the 32 MiB limit");
        }
        try {
            return mapper.readValue(bytes, type);
        } catch (IOException e) {
            throw new ProtocolException("Unable to decode JSON", e);
        }
    }

    public <T> T read(InputStream input, Class<T> type) {
        try {
            return mapper.readValue(input, type);
        } catch (IOException e) {
            throw new ProtocolException("Unable to decode JSON", e);
        }
    }

    public <T> T read(Path source, Class<T> type) throws IOException {
        long size = Files.size(source);
        if (size > MAX_JSON_BYTES) {
            throw new ProtocolException("JSON document exceeds the 32 MiB limit");
        }
        return read(Files.readAllBytes(source), type);
    }
}
