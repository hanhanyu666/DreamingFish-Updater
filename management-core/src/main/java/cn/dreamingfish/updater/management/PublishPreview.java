package cn.dreamingfish.updater.management;

import java.time.Instant;
import java.util.List;

public record PublishPreview(
        int schemaVersion,
        String previewId,
        String projectId,
        String baseReleaseId,
        Instant createdAt,
        List<ScannedFile> files,
        List<PreviewChange> changes,
        long totalManagedBytes,
        long estimatedDownloadBytes,
        ProjectRules rules,
        String projectDigest,
        List<PolicyChange> policyChanges,
        List<PreviewWarning> warnings
) {
    public PublishPreview {
        files = files == null ? List.of() : List.copyOf(files);
        changes = changes == null ? List.of() : List.copyOf(changes);
        policyChanges = policyChanges == null ? List.of() : List.copyOf(policyChanges);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public String confirmationDigest() {
        return cn.dreamingfish.updater.protocol.CryptoSupport.sha256(
                new cn.dreamingfish.updater.protocol.JsonCodec().write(this));
    }

    public boolean requiresPlayerProgramAcknowledgement() {
        return warnings.stream().anyMatch(warning ->
                PreviewWarning.PLAYER_PROGRAM_REQUIRED.equals(warning.code()));
    }

    PublishPreview withChanges(List<PreviewChange> replacement) {
        return new PublishPreview(schemaVersion, previewId, projectId, baseReleaseId, createdAt,
                files, replacement, totalManagedBytes, estimatedDownloadBytes, rules,
                projectDigest, policyChanges, warnings);
    }
}
