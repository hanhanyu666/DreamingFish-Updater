package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.CryptoSupport;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

final class ObjectDownloader {
    private static final int BUFFER_SIZE = 128 * 1024;
    private static final int DEFAULT_PARALLELISM = 4;
    private static final int MAX_PARALLELISM = 8;

    long download(UpdateRequest request, EnginePaths paths, Map<String, Long> objects,
                  ProgressListener listener) {
        if (objects.isEmpty()) return 0;
        request.cancellationToken().throwIfCancelled();
        long total = objects.values().stream().mapToLong(Long::longValue).sum();
        ProgressTracker tracker = new ProgressTracker(total, listener);
        int workerCount = Math.min(objects.size(), configuredParallelism());
        ExecutorService executor = Executors.newFixedThreadPool(workerCount, workerFactory());
        CompletionService<Long> completed = new ExecutorCompletionService<>(executor);
        List<Future<Long>> futures = new ArrayList<>(objects.size());
        try {
            for (Map.Entry<String, Long> object : objects.entrySet()) {
                String hash = object.getKey();
                long size = object.getValue();
                futures.add(completed.submit(() -> downloadOrUseCache(
                        request, paths, hash, size, tracker)));
            }
            long transferred = 0;
            for (int index = 0; index < objects.size(); index++) {
                request.cancellationToken().throwIfCancelled();
                try {
                    transferred += completed.take().get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new UpdateException(UpdateErrorCode.CANCELLED,
                            "Object downloads were interrupted", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof UpdateException updateException) throw updateException;
                    throw new UpdateException(UpdateErrorCode.DOWNLOAD_FAILED,
                            "Unable to download update objects", cause);
                }
            }
            return transferred;
        } finally {
            for (Future<Long> future : futures) future.cancel(true);
            executor.shutdownNow();
        }
    }

    private long downloadOrUseCache(UpdateRequest request, EnginePaths paths, String hash, long size,
                                    ProgressTracker tracker) {
        request.cancellationToken().throwIfCancelled();
        Path cached = paths.cacheObject(hash);
        if (isValid(cached, hash, size)) {
            tracker.set(hash, size, "正在使用已验证的本地缓存");
            return 0;
        }
        try {
            Files.deleteIfExists(cached);
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.DOWNLOAD_FAILED,
                    "Unable to replace corrupt cached object " + hash, e);
        }
        return downloadOne(request, paths, hash, size, tracker);
    }

    private long downloadOne(UpdateRequest request, EnginePaths paths, String hash, long expectedSize,
                             ProgressTracker tracker) {
        Path partial = paths.partialObject(hash);
        try {
            Files.createDirectories(partial.getParent());
            if (Files.exists(partial, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(partial);
            }
            long offset = Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS)
                    ? Files.size(partial) : 0;
            if (offset > expectedSize
                    || (offset == expectedSize && !isValid(partial, hash, expectedSize))) {
                Files.deleteIfExists(partial);
                offset = 0;
            }
            tracker.set(hash, offset, offset > 0 ? "正在继续上次下载" : "正在下载更新文件");
            if (offset == expectedSize && isValid(partial, hash, expectedSize)) {
                promote(partial, paths.cacheObject(hash));
                tracker.set(hash, expectedSize, "正在使用已完成的下载");
                return 0;
            }
            return performRequest(request, paths, hash, expectedSize, offset, tracker, false);
        } catch (UpdateException e) {
            throw e;
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.DOWNLOAD_FAILED,
                    "Unable to prepare object download " + hash, e);
        }
    }

    private long performRequest(UpdateRequest request, EnginePaths paths, String hash, long expectedSize,
                                long requestedOffset, ProgressTracker tracker, boolean retried) {
        request.cancellationToken().throwIfCancelled();
        URI uri = ManifestFetcher.endpoint(request.binding(), "v1/objects/sha256/" + hash);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(request.requestTimeout())
                .header("Accept-Encoding", "identity");
        if (requestedOffset > 0) builder.header("Range", "bytes=" + requestedOffset + "-");
        HttpResponse<InputStream> response;
        try {
            response = ManifestFetcher.client(request).send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateException(UpdateErrorCode.CANCELLED,
                    "Object download was interrupted", e);
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.DOWNLOAD_FAILED,
                    "Unable to download object " + hash, e);
        }

        try (InputStream input = response.body()) {
            int status = response.statusCode();
            long actualOffset;
            StandardOpenOption[] options;
            if (status == 206 && requestedOffset > 0
                    && validContentRange(response, requestedOffset, expectedSize)) {
                actualOffset = requestedOffset;
                options = new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND};
            } else if (status == 200) {
                actualOffset = 0;
                tracker.set(hash, 0, "正在重新下载文件");
                options = new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING};
            } else if (status == 416 && requestedOffset > 0 && !retried) {
                Files.deleteIfExists(paths.partialObject(hash));
                tracker.set(hash, 0, "正在重新下载文件");
                return performRequest(request, paths, hash, expectedSize, 0, tracker, true);
            } else {
                throw new UpdateException(UpdateErrorCode.DOWNLOAD_FAILED,
                        "Object request failed with HTTP " + status + " for " + hash);
            }

            Path partial = paths.partialObject(hash);
            long written = actualOffset;
            long networkBytes = 0;
            try (var output = Files.newOutputStream(partial, options)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    if (Thread.currentThread().isInterrupted()) {
                        throw new UpdateException(UpdateErrorCode.CANCELLED,
                                "Object download was interrupted");
                    }
                    request.cancellationToken().throwIfCancelled();
                    if (written + read > expectedSize) {
                        throw new UpdateException(UpdateErrorCode.HASH_MISMATCH,
                                "Downloaded object is larger than declared: " + hash);
                    }
                    output.write(buffer, 0, read);
                    written += read;
                    networkBytes += read;
                    tracker.set(hash, written, "正在下载更新文件");
                }
            }
            AtomicFileSupport.force(partial);
            if (!isValid(partial, hash, expectedSize)) {
                throw new UpdateException(UpdateErrorCode.HASH_MISMATCH,
                        "Downloaded object failed SHA-256 verification: " + hash);
            }
            promote(partial, paths.cacheObject(hash));
            tracker.set(hash, expectedSize, "正在下载更新文件");
            return networkBytes;
        } catch (UpdateException e) {
            throw e;
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.DOWNLOAD_FAILED,
                    "Unable to store object " + hash, e);
        }
    }

    private int configuredParallelism() {
        int configured = Integer.getInteger("dfs.download.parallelism", DEFAULT_PARALLELISM);
        return Math.max(1, Math.min(configured, MAX_PARALLELISM));
    }

    private ThreadFactory workerFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable,
                    "dreamingfish-download-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private boolean validContentRange(HttpResponse<?> response, long offset, long total) {
        String expected = "bytes " + offset + "-";
        return response.headers().firstValue("Content-Range")
                .map(value -> value.toLowerCase(Locale.ROOT).startsWith(expected)
                        && value.endsWith("/" + total))
                .orElse(false);
    }

    private boolean isValid(Path path, String hash, long size) {
        try {
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == size
                    && CryptoSupport.sha256(path).equals(hash);
        } catch (IOException e) {
            return false;
        }
    }

    private void promote(Path partial, Path cached) throws IOException {
        Files.createDirectories(cached.getParent());
        AtomicFileSupport.moveReplace(partial, cached);
        AtomicFileSupport.forceDirectory(cached.getParent());
    }

    private static final class ProgressTracker {
        private final long total;
        private final ProgressListener listener;
        private final Map<String, Long> byObject = new HashMap<>();
        private long completed;

        private ProgressTracker(long total, ProgressListener listener) {
            this.total = total;
            this.listener = listener;
        }

        synchronized void set(String hash, long bytes, String message) {
            long normalized = Math.max(0, bytes);
            long previous = byObject.getOrDefault(hash, 0L);
            byObject.put(hash, normalized);
            completed += normalized - previous;
            listener.onProgress(new ProgressEvent(UpdateStage.DOWNLOADING,
                    message, hash, Math.max(0, Math.min(completed, total)), total));
        }
    }
}
