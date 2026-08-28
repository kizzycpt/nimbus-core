package com.nimbus.api.site;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Owns everything under the sites root on disk.
 *
 * This class is the whole trust boundary for customer uploads, so it is
 * deliberately paranoid: every path a user supplies is validated segment by
 * segment and then re-checked against the site root after normalisation. It
 * never creates symlinks, and it never follows one it did not create.
 */
@Service
public class SiteStorageService {

    /** Lowercase DNS-safe: it becomes a container name and a URL segment. */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$");

    /** One path segment of an uploaded file. No dot-files, no traversal. */
    private static final Pattern SEGMENT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    private static final int MAX_DEPTH = 8;

    /**
     * Names that must never become a site: they collide with the app's own
     * routes, or with hostnames people expect to mean something else.
     */
    private static final List<String> RESERVED = List.of(
            "api", "www", "admin", "root", "status", "health", "login", "register",
            "dashboard", "account", "tickets", "sites", "assets", "static", "nimbus",
            "db", "backend", "web", "mail", "ftp", "ns1", "ns2", "localhost");

    private final Path root;
    private final long defaultQuotaBytes;
    private final long maxFileBytes;
    private final int maxSitesPerUser;

    public SiteStorageService(
            @Value("${app.sites.root:/var/nimbus/sites}") String root,
            @Value("${app.sites.default-quota-bytes:104857600}") long defaultQuotaBytes,
            @Value("${app.sites.max-file-bytes:10485760}") long maxFileBytes,
            @Value("${app.sites.max-per-user:3}") int maxSitesPerUser
    ) {
        this.root = Paths.get(root).toAbsolutePath().normalize();
        this.defaultQuotaBytes = defaultQuotaBytes;
        this.maxFileBytes = maxFileBytes;
        this.maxSitesPerUser = maxSitesPerUser;
    }

    @PostConstruct
    void ensureRoot() throws IOException {
        Files.createDirectories(root);
    }

    public long defaultQuotaBytes()  { return defaultQuotaBytes; }
    public long maxFileBytes()       { return maxFileBytes; }
    public int maxSitesPerUser()     { return maxSitesPerUser; }

    // ------------------------------------------------------------ validation

    public void validateSlug(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw new SiteException("Slug must be 3-32 characters: lowercase letters, "
                    + "digits and hyphens, starting and ending with a letter or digit");
        }
        if (RESERVED.contains(slug)) {
            throw new SiteException("'" + slug + "' is reserved");
        }
        if (slug.contains("--")) {
            // Reserved by the IDNA spec for punycode; keeps hostnames unambiguous.
            throw new SiteException("Slug must not contain a double hyphen");
        }
    }

    /**
     * Resolves a user-supplied relative path inside a site, or throws.
     *
     * Two independent checks have to pass: every segment must match a strict
     * whitelist, and the normalised result must still sit under the site root.
     * The second check is what catches anything the first one missed.
     */
    Path resolveInSite(String slug, String relativePath) {
        validateSlug(slug);
        Path siteRoot = publicDir(slug);

        if (relativePath == null || relativePath.isBlank()) {
            throw new SiteException("Path is required");
        }

        String cleaned = relativePath.replace('\\', '/').trim();

        // Reject an absolute path rather than quietly reinterpreting it as a
        // relative one. Stripping the leading slash would contain the write, but
        // it would also mean "/etc/passwd" silently succeeds as a site file —
        // an answer that looks like the attack worked and hides the mistake from
        // a caller who genuinely fat-fingered the path.
        if (cleaned.startsWith("/")) {
            throw new SiteException("Path must be relative to the site root, not absolute");
        }
        if (cleaned.isEmpty()) {
            throw new SiteException("Path is required");
        }
        if (cleaned.length() > 512) {
            throw new SiteException("Path is too long");
        }

        String[] segments = cleaned.split("/");
        if (segments.length > MAX_DEPTH) {
            throw new SiteException("Path is nested too deeply (max " + MAX_DEPTH + " levels)");
        }
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw new SiteException("Illegal path segment: '" + segment + "'. Use letters, "
                        + "digits, dot, underscore and hyphen; segments cannot start with a dot.");
            }
        }

        Path target = siteRoot.resolve(cleaned).normalize();
        if (!target.startsWith(siteRoot)) {
            // Unreachable given the segment whitelist — kept as the backstop that
            // actually enforces containment.
            throw new SiteException("Path escapes the site directory");
        }
        return target;
    }

    // --------------------------------------------------------- provisioning

    public Path siteDir(String slug)  { return root.resolve(slug).normalize(); }

    /** Files live one level down so site metadata never becomes web-reachable. */
    public Path publicDir(String slug) { return siteDir(slug).resolve("public").normalize(); }

    public void provision(String slug) {
        validateSlug(slug);
        try {
            Path pub = publicDir(slug);
            Files.createDirectories(pub);
            // World-readable so the site's nginx container (a different UID) can
            // serve the files without being given ownership of them.
            trySetPermissions(siteDir(slug), "rwxr-xr-x");
            trySetPermissions(pub, "rwxr-xr-x");

            Path index = pub.resolve("index.html");
            if (Files.notExists(index)) {
                Files.writeString(index, placeholderPage(slug));
                trySetPermissions(index, "rw-r--r--");
            }
        } catch (IOException e) {
            throw new SiteException("Could not create site directory: " + e.getMessage());
        }
    }

    public void destroy(String slug) {
        validateSlug(slug);
        Path dir = siteDir(slug);
        if (Files.notExists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            throw new SiteException("Could not remove site directory: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- files

    public void writeFile(String slug, String relativePath, InputStream content, long declaredSize) {
        if (declaredSize > maxFileBytes) {
            throw new SiteException("File is larger than the " + human(maxFileBytes) + " per-file limit");
        }

        Path target = resolveInSite(slug, relativePath);
        try {
            Files.createDirectories(target.getParent());

            // Write to a temp file in the same directory, then move it into place:
            // a failed or over-quota upload never leaves a half-written page live.
            Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                long written = Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
                if (written > maxFileBytes) {
                    throw new SiteException("File is larger than the "
                            + human(maxFileBytes) + " per-file limit");
                }
                trySetPermissions(temp, "rw-r--r--");
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new SiteException("Could not write file: " + e.getMessage());
        }
    }

    public void deleteFile(String slug, String relativePath) {
        Path target = resolveInSite(slug, relativePath);
        try {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new SiteException("Refusing to delete a directory");
            }
            if (!Files.deleteIfExists(target)) {
                throw new SiteException("No such file");
            }
        } catch (IOException e) {
            throw new SiteException("Could not delete file: " + e.getMessage());
        }
    }

    public List<SiteFile> listFiles(String slug) {
        validateSlug(slug);
        Path pub = publicDir(slug);
        if (Files.notExists(pub)) {
            return List.of();
        }
        List<SiteFile> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(pub, MAX_DEPTH)) {
            walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).forEach(p -> {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                    files.add(new SiteFile(
                            pub.relativize(p).toString(),
                            attrs.size(),
                            attrs.lastModifiedTime().toInstant()));
                } catch (IOException ignored) {
                    // A file that vanished mid-walk simply is not listed.
                }
            });
        } catch (IOException e) {
            throw new SiteException("Could not list files: " + e.getMessage());
        }
        files.sort(Comparator.comparing(SiteFile::path));
        return files;
    }

    /** Bytes on disk for one site. Walked rather than cached, so it cannot drift. */
    public long usedBytes(String slug) {
        Path dir = siteDir(slug);
        if (Files.notExists(dir)) {
            return 0L;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    // -------------------------------------------------------------- helpers

    private static void trySetPermissions(Path path, String posix) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(posix));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-POSIX filesystem: the container's umask already covers this.
        }
    }

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return "%.1f KB".formatted(bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return "%.1f MB".formatted(bytes / (1024.0 * 1024));
        return "%.2f GB".formatted(bytes / (1024.0 * 1024 * 1024));
    }

    private static String placeholderPage(String slug) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width,initial-scale=1" />
                  <title>%s</title>
                </head>
                <body style="font-family: system-ui, sans-serif; max-width: 40rem; margin: 4rem auto; padding: 0 1rem;">
                  <h1>%s is live</h1>
                  <p>This is the placeholder page. Upload an <code>index.html</code> to replace it.</p>
                </body>
                </html>
                """.formatted(slug, slug);
    }

    public record SiteFile(String path, long size, java.time.Instant modifiedAt) {}

    /** Signals a caller mistake; the controller turns it into a 400. */
    public static class SiteException extends RuntimeException {
        public SiteException(String message) { super(message); }
    }
}
