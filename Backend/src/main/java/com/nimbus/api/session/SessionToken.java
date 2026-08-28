package com.nimbus.api.session;

import com.nimbus.api.user.User;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * A server-side login session. The raw token is never stored — only its
 * SHA-256 hash — so a database leak cannot be replayed as a valid cookie.
 */
@Entity
@Table(
    name = "sessions",
    indexes = {
        @Index(name = "idx_sessions_token_hash", columnList = "tokenHash", unique = true),
        @Index(name = "idx_sessions_user", columnList = "user_id")
    }
)
public class SessionToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastSeenAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(length = 255)
    private String userAgent;

    @Column(length = 45)
    private String ip;

    protected SessionToken() {}

    public SessionToken(String tokenHash, User user, Instant now, Instant expiresAt,
                        String userAgent, String ip) {
        this.tokenHash = tokenHash;
        this.user = user;
        this.createdAt = now;
        this.lastSeenAt = now;
        this.expiresAt = expiresAt;
        this.userAgent = userAgent;
        this.ip = ip;
    }

    public Long getId()          { return id; }
    public String getTokenHash() { return tokenHash; }
    public User getUser()        { return user; }
    public Instant getCreatedAt(){ return createdAt; }
    public Instant getLastSeenAt(){ return lastSeenAt; }
    public Instant getExpiresAt(){ return expiresAt; }
    public String getUserAgent() { return userAgent; }
    public String getIp()        { return ip; }

    public void touch(Instant now, Instant newExpiry) {
        this.lastSeenAt = now;
        this.expiresAt = newExpiry;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
