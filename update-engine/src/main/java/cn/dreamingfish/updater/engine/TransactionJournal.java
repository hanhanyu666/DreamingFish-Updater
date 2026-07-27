package cn.dreamingfish.updater.engine;

import java.time.Instant;
import java.util.List;

record TransactionJournal(
        int schemaVersion,
        String id,
        TransactionPhase phase,
        String targetReleaseId,
        long targetSequence,
        Instant createdAt,
        List<TransactionEntry> entries,
        List<String> metadataFiles,
        String archiveDirectory
) {
    static final int SCHEMA_VERSION = 1;

    TransactionJournal {
        entries = entries == null ? List.of() : List.copyOf(entries);
        metadataFiles = metadataFiles == null ? List.of() : List.copyOf(metadataFiles);
    }

    TransactionJournal withPhase(TransactionPhase newPhase) {
        return new TransactionJournal(schemaVersion, id, newPhase, targetReleaseId,
                targetSequence, createdAt, entries, metadataFiles, archiveDirectory);
    }
}
