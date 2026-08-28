package com.nimbus.api.ticket;

import com.nimbus.api.user.User;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tickets", indexes = @Index(name = "idx_tickets_owner", columnList = "owner_id"))
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 120)
    private String subject;

    @Column(nullable = false, length = 4000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TicketKind kind = TicketKind.HELP;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TicketStatus status = TicketStatus.OPEN;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    private List<TicketComment> comments = new ArrayList<>();

    protected Ticket() {}

    public Ticket(User owner, String subject, String body, TicketKind kind) {
        this.owner = owner;
        this.subject = subject;
        this.body = body;
        this.kind = kind;
    }

    public Long getId()            { return id; }
    public User getOwner()         { return owner; }
    public String getSubject()     { return subject; }
    public String getBody()        { return body; }
    public TicketKind getKind()    { return kind; }
    public TicketStatus getStatus(){ return status; }
    public Instant getCreatedAt()  { return createdAt; }
    public Instant getUpdatedAt()  { return updatedAt; }
    public List<TicketComment> getComments() { return comments; }

    public void setStatus(TicketStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public void addComment(TicketComment comment) {
        comments.add(comment);
        this.updatedAt = Instant.now();
    }
}
