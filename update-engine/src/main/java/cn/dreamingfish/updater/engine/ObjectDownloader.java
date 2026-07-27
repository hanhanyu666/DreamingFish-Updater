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
import java.util.Locale;
import java.util.Map;

final class ObjectDownloader {
    private static final int BUFFER_SIZE = 128 * 1024;

    long download(UpdateRequest request, EnginePaths paths, Map<String, Long> objects,
                  ProgressListener listener) {
        long total = objects.values().stream().mapToLong(Long::longValue).sum();
        long alreadyAvailable = 0;
        long transferred = 0;
        for (Map.Entry<String, Long> object : objects.entrySet()) {
            request.cancellationToken().throwIfCancelled();
            String hash = object.getKey();
            long size = object.getValue();
            Path cached = paths.cacheObject(hash);
            if (isValid(cached, hash, size)) {
                alreadyAvailable += size;
                listener.onProgress(new ProgressEvent(UpdateStage.DOWNLOADING,
                        "Using verified cache", hash, alreadyAvailable, total));
                continue;
            }
            try {
                Files.deleteIfExists(cached);
            } catch (IOException e) {
                throw new UpdateException(UpdateErrorCode.DOWNLOAD_FAILED,
                        "Unable to replace corrupt cached object " + hash, e);
            }
            long base = alreadyAvailable;
            transferred += downloadOne(request, paths, hash, size, total, base, listener);
            alreadyAvailable += size;
        }
        return transferred;
    }

    private long downloadOne(UpdateRequest request, EnginePaths paths, String hash, long expectedSize,
                             long total, long completedBefore, ProgressListener listener) {
        Path partial = paths.partialObject(hash);
        try {
            Files.createDirectories(partial.getParent());
            if (Files.exists(partial, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(partial);
            }
            long offset = Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS)
                    ? Files.size(partial) : 0;
            if (offset > expectedSize || (offset == expectedSize && !isValid(partial, hash, expectedSize))) {
                Files.deleteIfExists(partial);
                offset = 0;
            }
            if (offset == expectedSize && isValid(partial, hash, expectedSize)) {
                promote(partial, paths.cacheObject(hash));
                return 0;
            }
            return performRequest(request, paths, hash, expectedSize, offset, total, completedBefore, listener, false);
        } catch (UpdateException e) {
            throw e;
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.DOWNLOAD_FAILED,
                    "Unable to prepare object download " + hash, e);
        }
    }

    private long performRequest(UpdateRequest request, EnginePaths paths, String hash, long expectedSize,
                                long requestedOffset, long total, long completedBefore,
                                ProgressListener listener, boolean retried) {
        URI uri = ManifestFetcher.endpoint(request.binding(), "v1/objects/sha256/" + hash);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(request.requestTimeout())
                .header("Accept-Encoding", "identity");
        if (requestedOffset > 0) builder.header("Range", "bytes=" + requestedOffset + "-");
        HttpResponse<InputStream> response;
        try {
            response = ManifestFetcher.client(request).send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateException(UpdateErrorCode.CANCELLED, "Object download was interrupted", e);
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.DOWNLOAD_FAILED,
                    "Unable to download object " + hash, e);
        }

        try (InputStream input = response.body()) {
            int status = response.statusCode();
            long actualOffset;
            StandardOpenOption[] options;
            if (status == 206 && requestedOffset > 0 && validContentRange(response, requestedOffset, expectedSize)) {
                actualOffset = requestedOffset;
                options = new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND};
            } else if (status == 200) {
                actualOffset = 0;
                options = new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING};
            } else if (status == 416 && requestedOffset > 0 && !retried) {
                Files.deleteIfExists(paths.partialObject(hash));
                return performRequest(request, paths, hash, expectedSize, 0, total,
                        completedBefore, listener, true);
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
                    request.cancellationToken().throwIfCancelled();
                    if (written + read > expectedSize) {
                        throw new UpdateException(UpdateErrorCode.HASH_MISMATCH,
                                "Downloaded object is larger than declared: " + hash);
                    }
                    output.write(buffer, 0, read);
                    written += read;
                    networkBytes += read;
                    listener.onProgress(new ProgressEvent(UpdateStage.DOWNLOADING,
                            "Downloading files", hash, completedBefore + written, total));
                }
            }
            AtomicFileSupport.force(partial);
            if (!isValid(partial, hash, expectedSize)) {
                throw new UpdateException(UpdateErrorCode.HASH_MISMATCH,
                        "Downloaded object failed SHA-256 verification: " + hash);
            }
            promote(partial, paths.cacheObject(hash));
            return networkBytes;
        } catch (UpdateException e) {
            throw e;
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.DOWNLOAD_FAILED,
                    "Unable to store object " + hash, e);
        }
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
}
