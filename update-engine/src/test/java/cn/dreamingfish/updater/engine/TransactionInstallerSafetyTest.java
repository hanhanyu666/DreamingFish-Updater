package cn.dreamingfish.updater.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionInstallerSafetyTest {
    @TempDir
    Path temporary;

    @Test
    void treatsEveryUnexpectedTransactionEntryAsPendingAndFailsClosed() throws Exception {
        EnginePaths paths = paths("unexpected-entry");
        Files.writeString(paths.transactions().resolve("not-a-transaction"), "unexpected");
        TransactionInstaller installer = new TransactionInstaller(new LocalInstallationStore());

        assertTrue(installer.hasPendingTransactions(paths));
        UpdateException failure = assertThrows(UpdateException.class,
                () -> installer.recover(paths, ProgressListener.NONE));
        assertEquals(UpdateErrorCode.RECOVERY_FAILED, failure.code());
    }

    @Test
    void rejectsASymbolicLinkTransactionJournalWhenSupported() throws Exception {
        EnginePaths paths = paths("linked-journal");
        Path transaction = Files.createDirectory(paths.transactions().resolve("transaction"));
        Path actual = temporary.resolve("outside-journal.json");
        Files.writeString(actual, "{}");
        try {
            Files.createSymbolicLink(transaction.resolve("journal.json"), actual);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return;
        }
        TransactionInstaller installer = new TransactionInstaller(new LocalInstallationStore());

        UpdateException failure = assertThrows(UpdateException.class,
                () -> installer.recover(paths, ProgressListener.NONE));
        assertEquals(UpdateErrorCode.RECOVERY_FAILED, failure.code());
    }

    private EnginePaths paths(String name) throws Exception {
        Path instance = Files.createDirectories(temporary.resolve(name).resolve("instance"));
        EnginePaths paths = EnginePaths.of(instance, instance.resolve("DreamingFishUpdater"));
        paths.createDirectories();
        return paths;
    }
}
