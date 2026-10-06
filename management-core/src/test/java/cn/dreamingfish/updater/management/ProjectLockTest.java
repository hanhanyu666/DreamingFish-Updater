package cn.dreamingfish.updater.management;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class ProjectLockTest {
    @TempDir Path temporary;
    @Test void nestedServiceLocksKeepOtherThreadsOutUntilTheOuterOperationCompletes() throws Exception {
        Path path = temporary.resolve("project.lock");
        try (var executor = Executors.newSingleThreadExecutor()) {
            try (ProjectLock outer = ProjectLock.acquire(path)) {
                try (ProjectLock inner = ProjectLock.acquire(path)) {}
                assertTrue(executor.submit(() -> {
                    try (ProjectLock conflicting = ProjectLock.acquire(path)) { return false; }
                    catch (ManagementException expected) { return true; }
                }).get());
            }
            assertTrue(executor.submit(() -> {
                try (ProjectLock available = ProjectLock.acquire(path)) { return true; }
            }).get());
        }
    }
}
