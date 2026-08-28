package com.nimbus.api.user;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant passwordChangedAt = Instant.now();

    /**
     * Total bytes this account may store across all of its sites. Set from
     * app.sites.default-quota-bytes at registration; raise it per account to
     * sell more space without touching the config.
     *
     * The explicit column default matters: ddl-auto=update cannot add a NOT NULL
     * column to a table that already has rows unless the database can fill them
     * in itself. Without it, deploying this change over an existing users table
     * fails at startup and every /me call 500s.
     */
    @Column(nullable = false, columnDefinition = "bigint not null default 104857600")
    private long storageQuotaBytes = 104_857_600L;

    public User() {}

    public User(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public long getStorageQuotaBytes() {
        return storageQuotaBytes;
    }

    public void setStorageQuotaBytes(long storageQuotaBytes) {
        this.storageQuotaBytes = storageQuotaBytes;
    }

    /** Only ever called with an already-encoded hash. */
    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
        this.passwordChangedAt = Instant.now();
    }
}
