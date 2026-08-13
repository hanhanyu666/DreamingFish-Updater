package cn.dreamingfish.updater.management;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ManagementDatabaseTest {
    @TempDir
    Path temporary;

    @Test
    void newProjectsHaveOnlyTheDocumentedAutomaticExclusions() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        ProjectRecord project = fixture.createProject();

        assertEquals(List.of(
                new FileRule(".dreamingfish-bootstrap/**", RuleAction.EXCLUDE),
                new FileRule("DreamingFishUpdater/**", RuleAction.EXCLUDE),
                new FileRule("logs/**", RuleAction.EXCLUDE),
                new FileRule("crash-reports/**", RuleAction.EXCLUDE),
                new FileRule("saves/**", RuleAction.EXCLUDE),
                new FileRule("screenshots/**", RuleAction.EXCLUDE)
        ), project.rules().rules());
    }

    @Test
    void removesLegacyDefaultRulesFromExistingProjectStorage() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        fixture.createProject();
        String legacyRules = """
                {"forcedSyncDirectories":[],"forcedSyncFiles":[],"rules":[
                  {"action":"EXCLUDE","glob":"logs/**"},
                  {"action":"DEFAULT","glob":"options.txt"},
                  {"action":"DEFAULT","glob":"servers.dat"}
                ]}
                """;

        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + fixture.paths.database());
             var statement = connection.prepareStatement(
                     "UPDATE projects SET rules_json = ? WHERE id = ?")) {
            statement.setString(1, legacyRules);
            statement.setString(2, "demo");
            assertEquals(1, statement.executeUpdate());
        }

        fixture.database.initialize();

        ProjectRules migrated = fixture.database.requireProject("demo").rules();
        assertEquals(List.of(new FileRule("logs/**", RuleAction.EXCLUDE)),
                migrated.rules());
        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + fixture.paths.database());
             var statement = connection.prepareStatement(
                     "SELECT rules_json FROM projects WHERE id = ?")) {
            statement.setString(1, "demo");
            try (var result = statement.executeQuery()) {
                assertFalse(result.getString(1).contains("DEFAULT"));
            }
        }
    }
}
