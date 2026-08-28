package com.nimbus.api.site;

import com.nimbus.api.user.User;
import com.nimbus.api.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provisioning and file management for customer websites.
 *
 * This is a control plane over the filesystem only. It never talks to Docker —
 * a network-facing application holding the Docker socket is root on the host.
 * The host-side reconciler watches the sites directory and creates or removes
 * the per-site containers to match.
 */
@RestController
@RequestMapping("/sites")
public class SiteController {

    private final SiteRepository sites;
    private final UserRepository users;
    private final SiteStorageService storage;

    public SiteController(SiteRepository sites, UserRepository users, SiteStorageService storage) {
        this.sites = sites;
        this.users = users;
        this.storage = storage;
    }

    // ---------------------------------------------------------------- read

    @GetMapping
    public Map<String, Object> list(Authentication authentication) {
        User user = require(authentication);
        List<Site> owned = sites.findByOwnerOrderByCreatedAtAsc(user);

        long used = owned.stream().mapToLong(s -> storage.usedBytes(s.getSlug())).sum();
        long quota = user.getStorageQuotaBytes();

        List<Map<String, Object>> entries = owned.stream().map(site -> {
            long siteBytes = storage.usedBytes(site.getSlug());
            return Map.<String, Object>of(
                    "slug", site.getSlug(),
                    "url", "/s/" + site.getSlug() + "/",
                    "createdAt", site.getCreatedAt(),
                    "updatedAt", site.getUpdatedAt(),
                    "fileCount", storage.listFiles(site.getSlug()).size(),
                    "usedBytes", siteBytes,
                    "usedHuman", SiteStorageService.human(siteBytes));
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sites", entries);
        body.put("quotaBytes", quota);
        body.put("quotaHuman", SiteStorageService.human(quota));
        body.put("usedBytes", used);
        body.put("usedHuman", SiteStorageService.human(used));
        body.put("remainingBytes", Math.max(0, quota - used));
        body.put("percentUsed", quota == 0 ? 0 : Math.min(100, (int) ((used * 100) / quota)));
        body.put("maxSites", storage.maxSitesPerUser());
        body.put("maxFileBytes", storage.maxFileBytes());
        return body;
    }

    @GetMapping("/{slug}/files")
    public List<Map<String, Object>> files(@PathVariable String slug, Authentication authentication) {
        owned(slug, authentication);
        return storage.listFiles(slug).stream()
                .map(f -> Map.<String, Object>of(
                        "path", f.path(),
                        "size", f.size(),
                        "sizeHuman", SiteStorageService.human(f.size()),
                        "modifiedAt", f.modifiedAt()))
                .toList();
    }

    // -------------------------------------------------------------- create

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody CreateRequest request, Authentication authentication) {
        User user = require(authentication);

        String slug = request.slug() == null ? "" : request.slug().trim().toLowerCase();
        storage.validateSlug(slug);

        if (sites.countByOwner(user) >= storage.maxSitesPerUser()) {
            return bad("You already have the maximum of " + storage.maxSitesPerUser() + " sites");
        }
        if (sites.existsBySlug(slug)) {
            // Slugs are global — they become hostnames and container names.
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "That name is already taken"));
        }

        Site site = sites.save(new Site(user, slug));
        storage.provision(slug);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "slug", site.getSlug(),
                "url", "/s/" + site.getSlug() + "/",
                "status", "provisioned",
                "note", "The serving container appears within a minute, once the reconciler picks it up."
        ));
    }

    // -------------------------------------------------------------- upload

    @PostMapping("/{slug}/files")
    @Transactional
    public ResponseEntity<?> upload(@PathVariable String slug,
                                    @RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "path", required = false) String path,
                                    Authentication authentication) {

        Site site = owned(slug, authentication);
        User user = site.getOwner();

        if (file == null || file.isEmpty()) {
            return bad("No file was uploaded");
        }

        // Prefer an explicit destination; otherwise fall back to the upload's own
        // name, stripped of any directory the browser may have included.
        String destination = (path == null || path.isBlank())
                ? baseName(file.getOriginalFilename())
                : path;

        long used = usedByUser(user);
        long quota = user.getStorageQuotaBytes();
        long replacing = existingSize(slug, destination);

        if (used - replacing + file.getSize() > quota) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                    "message", "Not enough space. Using " + SiteStorageService.human(used)
                            + " of " + SiteStorageService.human(quota) + "."));
        }

        try (var in = file.getInputStream()) {
            storage.writeFile(slug, destination, in, file.getSize());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Upload failed"));
        }

        site.touch();
        sites.save(site);

        long nowUsed = usedByUser(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "path", destination,
                "size", file.getSize(),
                "usedBytes", nowUsed,
                "usedHuman", SiteStorageService.human(nowUsed),
                "url", "/s/" + slug + "/" + destination
        ));
    }

    @DeleteMapping("/{slug}/files/**")
    @Transactional
    public ResponseEntity<?> deleteFile(@PathVariable String slug,
                                        org.springframework.web.context.request.WebRequest request,
                                        Authentication authentication) {
        Site site = owned(slug, authentication);

        // Everything after /sites/{slug}/files/ is the path inside the site.
        String full = request.getDescription(false);           // "uri=/sites/x/files/a/b.html"
        String marker = "/sites/" + slug + "/files/";
        int idx = full.indexOf(marker);
        if (idx < 0) {
            return bad("Path is required");
        }
        String relative = full.substring(idx + marker.length());

        storage.deleteFile(slug, relative);
        site.touch();
        sites.save(site);

        return ResponseEntity.ok(Map.of("status", "deleted", "path", relative));
    }

    // -------------------------------------------------------------- delete

    @DeleteMapping("/{slug}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable String slug, Authentication authentication) {
        Site site = owned(slug, authentication);
        sites.delete(site);
        storage.destroy(slug);
        return ResponseEntity.ok(Map.of(
                "status", "deleted",
                "note", "The serving container is removed on the next reconcile."));
    }

    // ------------------------------------------------------------- helpers

    private long usedByUser(User user) {
        return sites.findByOwnerOrderByCreatedAtAsc(user).stream()
                .mapToLong(s -> storage.usedBytes(s.getSlug()))
                .sum();
    }

    private long existingSize(String slug, String path) {
        return storage.listFiles(slug).stream()
                .filter(f -> f.path().equals(path))
                .mapToLong(SiteStorageService.SiteFile::size)
                .findFirst()
                .orElse(0L);
    }

    private static String baseName(String name) {
        if (name == null || name.isBlank()) {
            return "index.html";
        }
        String cleaned = name.replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        return slash >= 0 ? cleaned.substring(slash + 1) : cleaned;
    }

    private User require(Authentication authentication) {
        return users.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Account no longer exists"));
    }

    /** 404 rather than 403 for another account's site — never confirm it exists. */
    private Site owned(String slug, Authentication authentication) {
        return sites.findBySlugAndOwner(slug, require(authentication))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No such site"));
    }

    private static ResponseEntity<Map<String, String>> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    /** Turns the storage layer's validation failures into 400s, not 500s. */
    @ExceptionHandler(SiteStorageService.SiteException.class)
    public ResponseEntity<Map<String, String>> onSiteException(SiteStorageService.SiteException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    public record CreateRequest(String slug) {}
}
