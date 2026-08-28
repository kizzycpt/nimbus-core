package com.nimbus.api.session;

import com.nimbus.api.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<SessionToken, Long> {

    Optional<SessionToken> findByTokenHash(String tokenHash);

    List<SessionToken> findByUserOrderByLastSeenAtDesc(User user);

    void deleteByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from SessionToken s where s.user = :user")
    int deleteAllForUser(@Param("user") User user);

    @Modifying
    @Query("delete from SessionToken s where s.user = :user and s.id <> :keepId")
    int deleteAllForUserExcept(@Param("user") User user, @Param("keepId") Long keepId);

    @Modifying
    @Query("delete from SessionToken s where s.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
