package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Release-only entry point. Private signing material never enters a player bundle. */
public final class OfficialPlayerReleaseTool {
    private OfficialPlayerReleaseTool() {}
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("init-key")) {
            Path privateFile = Path.of(args[1]), publicFile = Path.of(args[2]);
            if (Files.exists(privateFile) || Files.exists(publicFile)) throw new IllegalArgumentException("Refusing to overwrite a signing identity");
            var key = CryptoSupport.generateEd25519KeyPair();
            Files.createDirectories(privateFile.toAbsolutePath().getParent());
            Files.createDirectories(publicFile.toAbsolutePath().getParent());
            Files.createFile(privateFile);
            var acl = Files.getFileAttributeView(privateFile, java.nio.file.attribute.AclFileAttributeView.class);
            if (acl != null) acl.setAcl(List.of(java.nio.file.attribute.AclEntry.newBuilder()
                    .setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                    .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class)).build()));
            try { Files.setPosixFilePermissions(privateFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { /* Use the owner's inherited Windows ACL. */ }
            Files.writeString(privateFile, CryptoSupport.encodePrivateKey(key.getPrivate()), StandardCharsets.US_ASCII, StandardOpenOption.WRITE);
            Files.writeString(publicFile, CryptoSupport.encodePublicKey(key.getPublic()) + "\n", StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
            System.out.println("Official player signing identity created; retain the private file outside public artifacts.");
            return;
        }
        if ((args.length == 5 || args.length == 6) && args[0].equals("verify")
                || args.length == 4 && args[0].equals("verify-catalog")) {
            var json = new JsonCodec();
            var signed = SignedDocument.decode(Files.readAllBytes(Path.of(args[1])), 2 * 1024 * 1024);
            var key = CryptoSupport.decodePublicKey(Files.readString(Path.of(args[2])).trim());
            if (!CryptoSupport.verify(signed.payload(), Base64.getDecoder().decode(signed.signature()), key)) throw new IllegalArgumentException("Invalid official player signature");
            var catalog = json.read(signed.payload(), OfficialPlayerCatalog.class); catalog.validate();
            if (catalog.releases().isEmpty()) throw new IllegalArgumentException("Empty player catalogue");
            for (var release : catalog.releases()) {
                if (!release.version().equals(args[0].equals("verify-catalog") ? args[3] : args[4])) throw new IllegalArgumentException("Wrong player version in official catalogue");
                if (args[0].equals("verify-catalog")) continue;
                if (args.length == 6 && !release.downloadUrl().startsWith(args[5])) throw new IllegalArgumentException("Official download URL does not match this release tag");
                String name = java.net.URI.create(release.downloadUrl()).getPath(); name = name.substring(name.lastIndexOf('/') + 1);
                Path archive = PathSafety.resolveInside(Path.of(args[3]), name);
                if (Files.size(archive) != release.archiveSize() || !CryptoSupport.sha256(archive).equals(release.archiveSha256()))
                    throw new IllegalArgumentException("Official archive changed after signing");
            }
            System.out.println("Official player catalogue signature, versions and archives verified."); return;
        }
        if (args.length != 9 || !args[0].equals("sign")) throw new IllegalArgumentException("Use init-key <private> <public>, verify <catalog> <public> <dist> <player-version>, or verify-catalog <catalog> <public> <player-version>, or sign <private> <public> <program-root> <zip> <url> <notes> <minimum-agent> <minimum-management>");
        var privateKey = CryptoSupport.decodePrivateKey(Files.readString(Path.of(args[1])).trim());
        var publicKey = CryptoSupport.decodePublicKey(Files.readString(Path.of(args[2])).trim());
        var source = PlayerProgramService.resolveSource(Path.of(args[3]), "", "");
        List<PlayerProgramFile> files = new ArrayList<>();
        try (var stream = Files.walk(source.root())) {
            for (Path path : stream.sorted().toList()) {
                PathSafety.assertSafePathTree(path);
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) files.add(new PlayerProgramFile(
                        source.root().relativize(path).toString().replace('\\', '/'), CryptoSupport.sha256(path), Files.size(path),
                        !source.launcher().endsWith(".exe") && Files.isExecutable(path)));
            }
        }
        Path zip = Path.of(args[4]);
        var release = new OfficialPlayerCatalog.Release(source.version(), source.launcher().endsWith(".exe") ? "windows-x64" : "linux-x64",
                args[5], CryptoSupport.sha256(zip), Files.size(zip), source.launcher(), args[7], args[8],
                Files.readString(Path.of(args[6]), StandardCharsets.UTF_8), Set.of(), files);
        var catalog = new OfficialPlayerCatalog(1, Instant.now(), List.of(release)); catalog.validate();
        byte[] payload = new JsonCodec().writePretty(catalog), signature = CryptoSupport.sign(payload, privateKey);
        if (!CryptoSupport.verify(payload, signature, publicKey)) throw new IllegalArgumentException("Private key does not match the pinned official public key");
        Files.write(zip.toAbsolutePath().getParent().resolve("player-catalog.signed"),
                new SignedDocument(payload, Base64.getEncoder().encodeToString(signature)).encode());
        System.out.println("Signed official player catalogue: " + source.version());
    }
}
