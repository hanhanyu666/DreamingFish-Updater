package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.ReleaseManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ManagementDatabase {
    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS projects (
                id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                source_path TEXT NOT NULL,
                public_base_url TEXT NOT NULL,
                public_key TEXT NOT NULL,
                private_key_file TEXT NOT NULL,
                branding_json TEXT NOT NULL,
                rules_json TEXT NOT NULL,
                next_sequence INTEGER NOT NULL CHECK (next_sequence > 0),
                created_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS releases (
                project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE RESTRICT,
                release_id TEXT NOT NULL,
                sequence INTEGER NOT NULL CHECK (sequence > 0),
                display_version TEXT NOT NULL,
                created_at TEXT NOT NULL,
                changelog TEXT NOT NULL,
                manifest_sha256 TEXT NOT NULL,
                signature TEXT NOT NULL,
                manifest_path TEXT NOT NULL,
                PRIMARY KEY (project_id, release_id),
                UNIQUE (project_id, sequence)
            );

            CREATE TABLE IF NOT EXISTS release_files (
                project_id TEXT NOT NULL,
                release_id TEXT NOT NULL,
                path TEXT NOT NULL,
                sha256 TEXT NOT NULL,
                size INTEGER NOT NULL CHECK (size >= 0),
                policy TEXT NOT NULL,
                executable INTEGER NOT NULL CHECK (executable IN (0, 1)),
                PRIMARY KEY (project_id, release_id, path),
                FOREIGN KEY (project_id, release_id)
                    REFERENCES releases(project_id, release_id) ON DELETE RESTRICT
            );

            CREATE TABLE IF NOT EXISTS objects (
                sha256 TEXT PRIMARY KEY,
                size INTEGER NOT NULL CHECK (size >= 0),
                reference_count INTEGER NOT NULL CHECK (reference_count >= 0),
                created_at TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_releases_latest
                ON releases(project_id, sequence DESC);
            CREATE INDEX IF NOT EXISTS idx_release_files_hash
                ON release_files(sha256);
            """;

    private final ManagementPaths paths;
    private final JsonCodec json;

    public ManagementDatabase(ManagementPaths paths, JsonCodec json) {
        this.paths = paths;
        this.json = json;
    }

    public void initialize() {
        try {
            paths.initialize();
            try (Connection connection = open(); Statement statement = connection.createStatement()) {
                for (String sql : SCHEMA.split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
            }
        } catch (IOException | SQLException e) {
            throw new ManagementException("Unable to initialize management storage", e);
        }
    }

    public void insertProject(ProjectRecord project) {
        String sql = """
                INSERT INTO projects (
                    id, display_name, source_path, public_base_url, public_key, private_key_file,
                    branding_json, rules_json, next_sequence, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, project.id());
            statement.setString(2, project.displayName());
            statement.setString(3, project.sourceDirectory().toAbsolutePath().normalize().toString());
            statement.setString(4, project.publicBaseUrl());
            statement.setString(5, project.publicKey());
            statement.setString(6, toStoredPath(project.privateKeyFile()));
            statement.setString(7, json.writeString(project.branding()));
            statement.setString(8, json.writeString(project.rules()));
            statement.setLong(9, project.nextSequence());
            statement.setString(10, project.createdAt().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new ManagementException("Unable to create project " + project.id(), e);
        }
    }

    public Optional<ProjectRecord> findProject(String projectId) {
        String sql = "SELECT * FROM projects WHERE id = ?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readProject(result)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new ManagementException("Unable to read project " + projectId, e);
        }
    }

    public ProjectRecord requireProject(String projectId) {
        return findProject(projectId)
                .orElseThrow(() -> new ManagementException("Unknown project: " + projectId));
    }

    public List<ProjectRecord> listProjects() {
        String sql = "SELECT * FROM projects ORDER BY id";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            List<ProjectRecord> projects = new ArrayList<>();
            while (result.next()) {
                projects.add(readProject(result));
            }
            return List.copyOf(projects);
        } catch (SQLException e) {
            throw new ManagementException("Unable to list projects", e);
        }
    }

    public void updateProject(String projectId, String displayName, Path source,
                              String publicBaseUrl, Branding branding, ProjectRules rules) {
        String sql = """
                UPDATE projects
                SET display_name = ?, source_path = ?, public_base_url = ?,
                    branding_json = ?, rules_json = ?
                WHERE id = ?
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, displayName);
            statement.setString(2, source.toAbsolutePath().normalize().toString());
            statement.setString(3, publicBaseUrl);
            statement.setString(4, json.writeString(branding));
            statement.setString(5, json.writeString(rules));
            statement.setString(6, projectId);
            if (statement.executeUpdate() != 1) {
                throw new ManagementException("Unknown project: " + projectId);
            }
        } catch (SQLException e) {
            throw new ManagementException("Unable to update project " + projectId, e);
        }
    }

    public Optional<StoredRelease> latestRelease(String projectId) {
        String sql = """
                SELECT * FROM releases
                WHERE project_id = ?
                ORDER BY sequence DESC
                LIMIT 1
                """;
        return queryRelease(sql, projectId, null);
    }

    public Optional<StoredRelease> findRelease(String projectId, String releaseId) {
        String sql = "SELECT * FROM releases WHERE project_id = ? AND release_id = ?";
        return queryRelease(sql, projectId, releaseId);
    }

    public List<StoredRelease> listReleases(String projectId) {
        String sql = "SELECT * FROM releases WHERE project_id = ? ORDER BY sequence DESC";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            try (ResultSet result = statement.executeQuery()) {
                List<StoredRelease> releases = new ArrayList<>();
                while (result.next()) {
                    releases.add(readRelease(result));
                }
                return List.copyOf(releases);
            }
        } catch (SQLException e) {
            throw new ManagementException("Unable to list releases for " + projectId, e);
        }
    }

    public ReleaseManifest readManifest(StoredRelease release) {
        try {
            return json.read(release.manifestPath(), ReleaseManifest.class);
        } catch (IOException e) {
            throw new ManagementException("Unable to read manifest " + release.manifestPath(), e);
        }
    }

    public void commitRelease(ReleaseManifest manifest, String signature, String manifestSha256,
                              Path manifestPath) {
        String insertRelease = """
                INSERT INTO releases (
                    project_id, release_id, sequence, display_version, created_at, changelog,
                    manifest_sha256, signature, manifest_path
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        String insertFile = """
                INSERT INTO release_files (
                    project_id, release_id, path, sha256, size, policy, executable
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        String upsertObject = """
                INSERT INTO objects (sha256, size, reference_count, created_at)
                VALUES (?, ?, 1, ?)
                ON CONFLICT(sha256) DO UPDATE SET
                    reference_count = objects.reference_count + 1
                """;
        String advanceProject = """
                UPDATE projects SET next_sequence = ?
                WHERE id = ? AND next_sequence = ?
                """;

        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement releaseStatement = connection.prepareStatement(insertRelease);
                 PreparedStatement fileStatement = connection.prepareStatement(insertFile);
                 PreparedStatement objectStatement = connection.prepareStatement(upsertObject);
                 PreparedStatement advanceStatement = connection.prepareStatement(advanceProject)) {
                releaseStatement.setString(1, manifest.projectId());
                releaseStatement.setString(2, manifest.releaseId());
                releaseStatement.setLong(3, manifest.sequence());
                releaseStatement.setString(4, manifest.displayVersion());
                releaseStatement.setString(5, manifest.createdAt().toString());
                releaseStatement.setString(6, manifest.changelog());
                releaseStatement.setString(7, manifestSha256);
                releaseStatement.setString(8, signature);
                releaseStatement.setString(9, toStoredPath(manifestPath));
                releaseStatement.executeUpdate();

                for (ManifestFile file : manifest.files()) {
                    fileStatement.setString(1, manifest.projectId());
                    fileStatement.setString(2, manifest.releaseId());
                    fileStatement.setString(3, file.path());
                    fileStatement.setString(4, file.sha256());
                    fileStatement.setLong(5, file.size());
                    fileStatement.setString(6, file.policy().name());
                    fileStatement.setInt(7, file.executable() ? 1 : 0);
                    fileStatement.addBatch();

                    objectStatement.setString(1, file.sha256());
                    objectStatement.setLong(2, file.size());
                    objectStatement.setString(3, manifest.createdAt().toString());
                    objectStatement.addBatch();
                }
                fileStatement.executeBatch();
                objectStatement.executeBatch();

                advanceStatement.setLong(1, manifest.sequence() + 1);
                advanceStatement.setString(2, manifest.projectId());
                advanceStatement.setLong(3, manifest.sequence());
                if (advanceStatement.executeUpdate() != 1) {
                    throw new ManagementException("Project changed while the release was being committed");
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                if (e instanceof ManagementException managementException) {
                    throw managementException;
                }
                throw new ManagementException("Unable to commit release " + manifest.releaseId(), e);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new ManagementException("Unable to commit release " + manifest.releaseId(), e);
        }
    }

    public void createConsistentSnapshot(Path target) {
        try {
            java.nio.file.Files.deleteIfExists(target);
            String escaped = target.toAbsolutePath().normalize().toString().replace("'", "''");
            try (Connection connection = open(); Statement statement = connection.createStatement()) {
                statement.execute("VACUUM INTO '" + escaped + "'");
            }
        } catch (IOException | SQLException e) {
            throw new ManagementException("Unable to create a consistent database snapshot", e);
        }
    }

    private Optional<StoredRelease> queryRelease(String sql, String projectId, String releaseId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            if (releaseId != null) {
                statement.setString(2, releaseId);
            }
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readRelease(result)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new ManagementException("Unable to read release", e);
        }
    }

    private ProjectRecord readProject(ResultSet result) throws SQLException {
        Branding branding = json.read(
                result.getString("branding_json").getBytes(StandardCharsets.UTF_8), Branding.class);
        ProjectRules rules = json.read(
                result.getString("rules_json").getBytes(StandardCharsets.UTF_8), ProjectRules.class);
        return new ProjectRecord(
                result.getString("id"),
                result.getString("display_name"),
                Path.of(result.getString("source_path")),
                result.getString("public_base_url"),
                result.getString("public_key"),
                fromStoredPath(result.getString("private_key_file")),
                branding,
                rules,
                result.getLong("next_sequence"),
                Instant.parse(result.getString("created_at"))
        );
    }

    private StoredRelease readRelease(ResultSet result) throws SQLException {
        return new StoredRelease(
                result.getString("project_id"),
                result.getString("release_id"),
                result.getLong("sequence"),
                result.getString("display_version"),
                Instant.parse(result.getString("created_at")),
                result.getString("changelog"),
                result.getString("manifest_sha256"),
                result.getString("signature"),
                fromStoredPath(result.getString("manifest_path"))
        );
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + paths.database());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 10000");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = FULL");
        }
        return connection;
    }

    private String toStoredPath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(paths.root())) {
            throw new ManagementException("Managed data path escapes the data root: " + path);
        }
        return paths.root().relativize(absolute).toString().replace('\\', '/');
    }

    private Path fromStoredPath(String path) {
        Path resolved = paths.root().resolve(path.replace('/', java.io.File.separatorChar)).normalize();
        if (!resolved.startsWith(paths.root())) {
            throw new ManagementException("Stored path escapes the data root: " + path);
        }
        return resolved;
    }
}
