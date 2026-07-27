package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ProjectRecord;
import cn.dreamingfish.updater.management.StoredRelease;

import java.util.LinkedHashMap;
import java.util.Map;

final class CliOutput {
    private CliOutput() {
    }

    static void project(ManagementCli root, ProjectRecord project) {
        if (root.jsonOutput) {
            root.printJson(projectMap(project));
        } else {
            root.out().printf("%s  %s%n", project.id(), project.displayName());
            root.out().println("  Source: " + project.sourceDirectory());
            root.out().println("  Public URL: " + project.publicBaseUrl());
            root.out().println("  Forced sync: " + (project.rules().forcedSyncDirectories().isEmpty()
                    ? "disabled"
                    : String.join(", ", project.rules().forcedSyncDirectories())));
            root.out().println("  Next sequence: " + project.nextSequence());
            root.out().println("  Public key: " + project.publicKey());
        }
    }

    static Map<String, Object> projectMap(ProjectRecord project) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", project.id());
        value.put("displayName", project.displayName());
        value.put("sourceDirectory", project.sourceDirectory().toString());
        value.put("publicBaseUrl", project.publicBaseUrl());
        value.put("publicKey", project.publicKey());
        value.put("branding", project.branding());
        value.put("rules", project.rules());
        value.put("nextSequence", project.nextSequence());
        value.put("createdAt", project.createdAt());
        return value;
    }

    static Map<String, Object> releaseMap(StoredRelease release) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("projectId", release.projectId());
        value.put("releaseId", release.releaseId());
        value.put("sequence", release.sequence());
        value.put("displayVersion", release.displayVersion());
        value.put("createdAt", release.createdAt());
        value.put("changelog", release.changelog());
        value.put("manifestSha256", release.manifestSha256());
        return value;
    }
}
