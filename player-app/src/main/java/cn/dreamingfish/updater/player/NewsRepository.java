package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.protocol.JsonCodec;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class NewsRepository {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_INDEX_BYTES = 256 * 1024;
    private static final int MAX_ARTICLE_BYTES = 1024 * 1024;
    private static final Pattern ARTICLE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern RESOURCE_SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

    private NewsRepository() {
    }

    static List<NewsArticle> loadBundled() {
        URL indexUrl = requiredResource("news/index.json");
        NewsIndex index;
        try (InputStream input = indexUrl.openStream()) {
            index = new JsonCodec().read(readLimited(input, MAX_INDEX_BYTES, "新闻索引"), NewsIndex.class);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取内置新闻索引", e);
        }
        if (index.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalStateException("不支持的新闻索引版本：" + index.schemaVersion());
        }
        if (index.articles() == null) throw new IllegalStateException("新闻索引缺少 articles");

        Set<String> ids = new HashSet<>();
        return index.articles().stream()
                .map(metadata -> loadArticle(metadata, ids))
                .sorted(Comparator.comparing(NewsArticle::publishedOn).reversed()
                        .thenComparing(NewsArticle::title))
                .toList();
    }

    static URL resolveImage(String destination) {
        if (destination == null || destination.isBlank()) return null;
        String value = destination.trim();
        try {
            URI uri = URI.create(value);
            if (uri.isAbsolute()) {
                String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
                return (scheme.equals("https") || scheme.equals("http"))
                        ? uri.toURL() : null;
            }
        } catch (IllegalArgumentException | MalformedURLException ignored) {
            return null;
        }
        try {
            return optionalResource(validateResourcePath(value));
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private static NewsArticle loadArticle(NewsMetadata metadata, Set<String> ids) {
        if (metadata == null) throw new IllegalStateException("新闻索引包含空文章");
        String id = requiredText(metadata.id(), "文章 ID", 64);
        if (!ARTICLE_ID.matcher(id).matches()) throw new IllegalStateException("文章 ID 无效：" + id);
        if (!ids.add(id)) throw new IllegalStateException("文章 ID 重复：" + id);
        String title = requiredText(metadata.title(), "文章标题", 120);
        String summary = requiredText(metadata.summary(), "文章摘要", 360);
        LocalDate publishedOn = metadata.publishedOn();
        if (publishedOn == null) throw new IllegalStateException("文章缺少发布日期：" + id);

        URL cover = requiredResource(validateResourcePath(metadata.cover()));
        String bodyPath = validateResourcePath(metadata.body());
        if (!bodyPath.toLowerCase(Locale.ROOT).endsWith(".md")) {
            throw new IllegalStateException("新闻正文必须是 Markdown 文件：" + bodyPath);
        }
        URL body = requiredResource(bodyPath);
        String markdown;
        try (InputStream input = body.openStream()) {
            markdown = new String(readLimited(input, MAX_ARTICLE_BYTES, "新闻正文"),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取新闻正文：" + bodyPath, e);
        }
        if (markdown.isBlank()) throw new IllegalStateException("新闻正文为空：" + bodyPath);
        return new NewsArticle(id, title, summary, publishedOn, cover, markdown);
    }

    private static String requiredText(String value, String label, int maximumLength) {
        if (value == null || value.isBlank()) throw new IllegalStateException(label + "不能为空");
        String normalized = value.strip();
        if (normalized.length() > maximumLength) {
            throw new IllegalStateException(label + "超过 " + maximumLength + " 个字符");
        }
        return normalized;
    }

    private static String validateResourcePath(String value) {
        if (value == null || value.isBlank()) throw new IllegalStateException("新闻资源路径不能为空");
        String normalized = value.strip();
        if (normalized.startsWith("/") || normalized.contains("\\") || normalized.contains(":")) {
            throw new IllegalStateException("新闻资源路径无效：" + normalized);
        }
        String[] segments = normalized.split("/", -1);
        if (segments.length < 2) throw new IllegalStateException("新闻资源必须位于子目录：" + normalized);
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")
                    || !RESOURCE_SEGMENT.matcher(segment).matches()) {
                throw new IllegalStateException("新闻资源路径无效：" + normalized);
            }
        }
        return String.join("/", segments);
    }

    private static URL requiredResource(String path) {
        URL resource = optionalResource(path);
        if (resource == null) throw new IllegalStateException("找不到新闻资源：" + path);
        return resource;
    }

    private static URL optionalResource(String path) {
        return NewsRepository.class.getResource(path);
    }

    private static byte[] readLimited(InputStream input, int maximumBytes, String label) throws IOException {
        byte[] bytes = input.readNBytes(maximumBytes + 1);
        if (bytes.length > maximumBytes) throw new IllegalStateException(label + "超过大小限制");
        return bytes;
    }

    private record NewsIndex(int schemaVersion, List<NewsMetadata> articles) {
    }

    private record NewsMetadata(
            String id,
            String title,
            String summary,
            LocalDate publishedOn,
            String cover,
            String body
    ) {
    }
}
