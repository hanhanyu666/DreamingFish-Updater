package cn.dreamingfish.updater.bootstrap;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

final class BindingReader {
    private static final long MAX_BINDING_BYTES = 1024L * 1024L;

    Path readPlayerHome(Path bindingFile, Path instanceRoot) throws BootstrapException {
        return read(bindingFile, instanceRoot).playerHome();
    }

    BootstrapBinding read(Path bindingFile, Path instanceRoot) throws BootstrapException {
        try {
            if (!Files.isRegularFile(bindingFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(bindingFile)
                    || Files.size(bindingFile) > MAX_BINDING_BYTES) {
                throw new BootstrapException("Project binding is missing or too large: " + bindingFile);
            }
            String playerHome = null;
            String projectId = null;
            String publicKey = null;
            JsonFactory factory = new JsonFactory();
            factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            InputStream input = Files.newInputStream(bindingFile);
            try {
                JsonParser parser = factory.createParser(input);
                try {
                    if (parser.nextToken() != JsonToken.START_OBJECT) {
                        throw new BootstrapException("Project binding must be a JSON object");
                    }
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        String field = parser.currentName();
                        JsonToken valueToken = parser.nextToken();
                        if ("playerHome".equals(field)) {
                            if (valueToken != JsonToken.VALUE_STRING || playerHome != null) {
                                throw new BootstrapException("Project binding has an invalid playerHome");
                            }
                            playerHome = parser.getValueAsString();
                        } else if ("projectId".equals(field)) {
                            if (valueToken != JsonToken.VALUE_STRING || projectId != null) {
                                throw new BootstrapException("Project binding has an invalid projectId");
                            }
                            projectId = parser.getValueAsString();
                        } else if ("publicKey".equals(field)) {
                            if (valueToken != JsonToken.VALUE_STRING || publicKey != null) {
                                throw new BootstrapException("Project binding has an invalid publicKey");
                            }
                            publicKey = parser.getValueAsString();
                        } else {
                            parser.skipChildren();
                        }
                    }
                    if (parser.nextToken() != null) {
                        throw new BootstrapException("Project binding has trailing JSON content");
                    }
                } finally {
                    parser.close();
                }
            } finally {
                input.close();
            }
            if (playerHome == null || playerHome.trim().isEmpty() || playerHome.length() > 4096) {
                throw new BootstrapException("Project binding does not contain a usable playerHome");
            }
            Path configured = Paths.get(playerHome);
            Path resolved = configured.isAbsolute()
                    ? configured.toAbsolutePath().normalize()
                    : instanceRoot.resolve(configured).toAbsolutePath().normalize();
            if (resolved.equals(instanceRoot.toAbsolutePath().normalize())) {
                throw new BootstrapException("Player updater directory cannot be the instance root");
            }
            if (resolved.startsWith(instanceRoot.resolve(".dreamingfish-bootstrap")
                    .toAbsolutePath().normalize())) {
                throw new BootstrapException("Player updater directory cannot be inside the bootstrap directory");
            }
            return new BootstrapBinding(resolved, projectId, publicKey);
        } catch (BootstrapException e) {
            throw e;
        } catch (Exception e) {
            throw new BootstrapException("Unable to read project binding " + bindingFile, e);
        }
    }
}
