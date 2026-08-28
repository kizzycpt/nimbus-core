package com.nimbus.api.ticket;

import com.nimbus.api.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findByOwnerOrderByUpdatedAtDesc(User owner);

    Optional<Ticket> findByIdAndOwner(Long id, User owner);

    long countByOwnerAndStatusNot(User owner, TicketStatus status);

    @Modifying
    @Query("delete from TicketComment c where c.ticket.owner = :owner")
    void deleteCommentsByOwner(@Param("owner") User owner);

    @Modifying
    @Query("delete from Ticket t where t.owner = :owner")
    void deleteTicketsByOwner(@Param("owner") User owner);

    default void deleteAllByOwner(User owner) {
        deleteCommentsByOwner(owner);
        deleteTicketsByOwner(owner);
    }
}
