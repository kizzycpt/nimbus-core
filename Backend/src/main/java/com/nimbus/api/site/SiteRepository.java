package com.nimbus.api.site;

import com.nimbus.api.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SiteRepository extends JpaRepository<Site, Long> {

    List<Site> findByOwnerOrderByCreatedAtAsc(User owner);

    Optional<Site> findBySlugAndOwner(String slug, User owner);

    Optional<Site> findBySlug(String slug);

    boolean existsBySlug(String slug);

    long countByOwner(User owner);

    @Modifying
    @Query("delete from Site s where s.owner = :owner")
    void deleteAllByOwner(@Param("owner") User owner);
}
