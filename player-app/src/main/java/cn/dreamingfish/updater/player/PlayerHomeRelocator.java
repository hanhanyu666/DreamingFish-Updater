package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ProjectBinding;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;

final class PlayerHomeRelocator {
    private final JsonCodec json = new JsonCodec();

    ProjectBinding relocate(Path currentHome, Path targetHome, Path instanceRoot,
                            Path bindingFile, ProjectBinding binding) throws IOException {
        Path source = currentHome.toAbsolutePath().normalize();
        Path target = targetHome.toAbsolutePath().normalize();
        if (source.equals(target)) return binding;
        Path instance = instanceRoot.toAbsolutePath().normalize();
        if (target.equals(instance)) {
            throw new IOException("Player updater directory cannot be the instance root");
        }
        if (target.startsWith(source) || source.startsWith(target)) {
            throw new IOException("Player updater cannot be moved into or around its current directory");
        }
        if (target.startsWith(instance.resolve(".dreamingfish-bootstrap"))) {
            throw new IOException("Player updater cannot be stored inside the bootstrap directory");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                throw new IOException("Selected player updater location is not a regular directory");
            }
            try (var entries = Files.list(target)) {
                if (entries.findAny().isPresent()) {
                    throw new IOException("Selected player updater location must be empty");
                }
            }
        } else {
            Files.createDirectories(target);
        }
        copyAndVerify(source, target);
        Files.createDirectories(target.resolve("state"));
        Files.writeString(target.resolve("state/first-run-complete"), "1\n");

        ProjectBinding relocated = binding.withPlayerHome(target.toString());
        writeAtomically(bindingFile, json.write(relocated));
        return relocated;
    }

    private void copyAndVerify(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw new IOException("Current player updater directory is invalid");
        }
        try (var stream = Files.walk(source)) {
            for (Path path : stream.sorted(Comparator.naturalOrder()).toList()) {
                Path relative = source.relativize(path);
                if (relative.toString().isEmpty()) continue;
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Player updater directory contains a symbolic link: " + relative);
                }
                Path destination = target.resolve(relative).normalize();
                if (!destination.startsWith(target)) throw new IOException("Player updater path escapes target");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES,
                            StandardCopyOption.REPLACE_EXISTING);
                    if (Files.size(path) != Files.size(destination)
                            || !CryptoSupport.sha256(path).equals(CryptoSupport.sha256(destination))) {
                        throw new IOException("Copied player updater file failed verification: " + relative);
                    }
                } else {
                    throw new IOException("Unsupported player updater file: " + relative);
                }
            }
        }
    }

    private void writeAtomically(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
