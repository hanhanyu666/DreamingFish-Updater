package cn.dreamingfish.updater.protocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PathSafety {
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );
    private static final String WINDOWS_FORBIDDEN = "<>:\"|?*";

    private PathSafety() {
    }

    public static String normalizeManifestPath(String input) {
        if (input == null || input.isBlank()) {
            throw new ProtocolException("Manifest path is empty");
        }
        if (input.startsWith("/") || input.endsWith("/") || input.contains("\\") || input.contains("//")) {
            throw new ProtocolException("Manifest path must be a normalized relative path: " + input);
        }
        if (input.indexOf('\0') >= 0) {
            throw new ProtocolException("Manifest path contains a NUL byte");
        }

        String[] segments = input.split("/", -1);
        for (String segment : segments) {
            validateSegment(segment, input);
        }
        return String.join("/", segments);
    }

    private static void validateSegment(String segment, String fullPath) {
        if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
            throw new ProtocolException("Manifest path contains an unsafe segment: " + fullPath);
        }
        if (segment.endsWith(" ") || segment.endsWith(".")) {
            throw new ProtocolException("Windows trims the final character of this path segment: " + fullPath);
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c < 32 || WINDOWS_FORBIDDEN.indexOf(c) >= 0) {
                throw new ProtocolException("Manifest path contains a Windows-forbidden character: " + fullPath);
            }
        }
        String baseName = segment;
        int extension = segment.indexOf('.');
        if (extension >= 0) {
            baseName = segment.substring(0, extension);
        }
        if (WINDOWS_RESERVED.contains(baseName.toUpperCase(Locale.ROOT))) {
            throw new ProtocolException("Manifest path uses a Windows reserved device name: " + fullPath);
        }
    }

    public static void validateDistinctPaths(List<String> paths) {
        Set<String> folded = new HashSet<>();
        for (String path : paths) {
            String normalized = normalizeManifestPath(path);
            if (!folded.add(normalized.toLowerCase(Locale.ROOT))) {
                throw new ProtocolException("Manifest contains a case-insensitive path collision: " + path);
            }
        }
    }

    public static Path resolveInside(Path root, String manifestPath) throws IOException {
        String normalized = normalizeManifestPath(manifestPath);
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path candidate = absoluteRoot.resolve(normalized.replace('/', java.io.File.separatorChar)).normalize();
        if (!candidate.startsWith(absoluteRoot)) {
            throw new ProtocolException("Path escapes the instance directory: " + manifestPath);
        }
        assertNoSymbolicLinkTraversal(absoluteRoot, candidate);
        return candidate;
    }

    private static void assertNoSymbolicLinkTraversal(Path root, Path candidate) throws IOException {
        Path current = root;
        Path relative = root.relativize(candidate);
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new ProtocolException("Path traverses a symbolic link: " + current);
            }
        }
    }
}
