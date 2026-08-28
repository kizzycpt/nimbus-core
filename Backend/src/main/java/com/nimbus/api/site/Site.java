package com.nimbus.api.site;

import com.nimbus.api.user.User;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * A customer website.
 *
 * The row is metadata only. The actual files live on disk under
 * {@code <sites-root>/<slug>/public}, and the container that serves them is
 * created by the host-side reconciler, not by this application.
 */
@Entity
@Table(
    name = "sites",
    indexes = {
        @Index(name = "idx_sites_slug", columnList = "slug", unique = true),
        @Index(name = "idx_sites_owner", columnList = "owner_id")
    }
)
public class Site {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    /** Also the container name suffix and the URL path segment. */
    @Column(nullable = false, unique = true, length = 32)
    private String slug;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected Site() {}

    public Site(User owner, String slug) {
        this.owner = owner;
        this.slug = slug;
    }

    public Long getId()           { return id; }
    public User getOwner()        { return owner; }
    public String getSlug()       { return slug; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
