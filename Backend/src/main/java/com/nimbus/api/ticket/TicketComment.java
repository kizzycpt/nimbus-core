package com.nimbus.api.ticket;

import com.nimbus.api.user.User;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "ticket_comments",
       indexes = @Index(name = "idx_comments_ticket", columnList = "ticket_id"))
public class TicketComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Column(nullable = false, length = 4000)
    private String body;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected TicketComment() {}

    public TicketComment(Ticket ticket, User author, String body) {
        this.ticket = ticket;
        this.author = author;
        this.body = body;
    }

    public Long getId()           { return id; }
    public Ticket getTicket()     { return ticket; }
    public User getAuthor()       { return author; }
    public String getBody()       { return body; }
    public Instant getCreatedAt() { return createdAt; }
}
