package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.ManifestValidator;
import cn.dreamingfish.updater.protocol.ProjectBinding;
import cn.dreamingfish.updater.protocol.ProtocolConstants;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

public final class ProjectService {
    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final ProjectKeyStore keys;
    private final ObjectStore objects;

    public ProjectService(ManagementPaths paths, ManagementDatabase database) {
        this.paths = paths;
        this.database = database;
        this.keys = new ProjectKeyStore(paths);
        this.objects = new ObjectStore(paths);
    }

    public ProjectRecord create(String id, String displayName, Path sourceDirectory,
                                String publicBaseUrl, Branding branding, ProjectRules rules) {
        validateProjectId(id);
        Path source = validateSource(sourceDirectory);
        String baseUrl = normalizeBaseUrl(publicBaseUrl);
        ProjectKeyStore.KeyMaterial key = keys.create(id);
        ProjectRecord project = new ProjectRecord(
                id,
                displayName,
                source,
                baseUrl,
                key.publicKey(),
                key.privateKeyFile(),
                branding == null ? Branding.empty() : branding,
                rules == null ? ProjectRules.defaults() : rules,
                1,
                Instant.now()
        );
        try {
            ManifestValidator.validateBinding(bindingFor(project, "DreamingFishUpdater", null));
            database.insertProject(project);
            return project;
        } catch (RuntimeException e) {
            try {
                Files.deleteIfExists(key.privateKeyFile());
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    public ProjectRecord configure(String id, Path sourceDirectory, String publicBaseUrl,
                                   Branding branding, ProjectRules rules) {
        ProjectRecord current = database.requireProject(id);
        Path source = sourceDirectory == null ? current.sourceDirectory() : validateSource(sourceDirectory);
        String url = publicBaseUrl == null ? current.publicBaseUrl() : normalizeBaseUrl(publicBaseUrl);
        Branding nextBranding = branding == null ? current.branding() : branding;
        ProjectRules nextRules = rules == null ? current.rules() : rules;
        ProjectRecord candidate = new ProjectRecord(
                current.id(), current.displayName(), source, url, current.publicKey(), current.privateKeyFile(),
                nextBranding, nextRules, current.nextSequence(), current.createdAt()
        );
        ManifestValidator.validateBinding(bindingFor(candidate, "DreamingFishUpdater", null));
        new RuleSet(nextRules);
        database.updateProject(id, source, url, nextBranding, nextRules);
        return database.requireProject(id);
    }

    public ProjectBinding bindingFor(ProjectRecord project, String playerHome, String bundledCoverPath) {
        return new ProjectBinding(
                ProtocolConstants.BINDING_SCHEMA_VERSION,
                project.id(),
                project.publicBaseUrl(),
                project.publicKey(),
                playerHome,
                bundledCoverPath,
                project.branding()
        );
    }

    public List<ProjectRecord> list() {
        return database.listProjects();
    }

    public ProjectRecord setCover(String id, Path coverFile) {
        if (!Files.isRegularFile(coverFile)) {
            throw new ManagementException("Cover image does not exist: " + coverFile);
        }
        ProjectRecord current = database.requireProject(id);
        ObjectStore.ObjectInfo cover = objects.importFile(coverFile);
        Branding old = current.branding();
        Branding branding = new Branding(
                old.productName(), old.subtitle(), old.serverAddress(), cover.sha256(),
                old.accentColor(), old.secondaryAccentColor()
        );
        database.updateProject(id, current.sourceDirectory(), current.publicBaseUrl(), branding, current.rules());
        return database.requireProject(id);
    }

    private Path validateSource(Path sourceDirectory) {
        if (sourceDirectory == null) {
            throw new ManagementException("A standard modpack source directory is required");
        }
        Path source = sourceDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new ManagementException("Standard modpack source directory does not exist: " + source);
        }
        if (Files.isSymbolicLink(source)) {
            throw new ManagementException("Standard modpack source directory cannot be a symbolic link: " + source);
        }
        if (source.startsWith(paths.root()) || paths.root().startsWith(source)) {
            throw new ManagementException("The standard modpack source and management data directories must be separate");
        }
        return source;
    }

    private static void validateProjectId(String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new ManagementException("Project ID must use lowercase letters, digits, dots, underscores, or hyphens");
        }
    }

    static String normalizeBaseUrl(String input) {
        try {
            URI uri = new URI(input);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new ManagementException("Public base URL must be an absolute HTTP(S) URL");
            }
            String value = uri.toString();
            while (value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }
            return value;
        } catch (URISyntaxException | NullPointerException e) {
            throw new ManagementException("Invalid public base URL", e);
        }
    }
}
