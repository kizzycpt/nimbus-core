package com.nimbus.api.ticket;

import com.nimbus.api.user.User;
import com.nimbus.api.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Help and change-request tickets. Every query is scoped to the calling user —
 * there is no path that reads another account's ticket.
 */
@RestController
@RequestMapping("/tickets")
public class TicketController {

    private static final int MAX_SUBJECT = 120;
    private static final int MAX_BODY = 4000;

    private final TicketRepository tickets;
    private final UserRepository users;

    public TicketController(TicketRepository tickets, UserRepository users) {
        this.tickets = tickets;
        this.users = users;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Authentication authentication) {
        return tickets.findByOwnerOrderByUpdatedAtDesc(require(authentication)).stream()
                .map(TicketController::summary)
                .toList();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody TicketRequest request,
                                    Authentication authentication) {

        String subject = trimmed(request.subject());
        String body = trimmed(request.body());

        if (subject.isEmpty() || body.isEmpty()) {
            return bad("Subject and body are both required");
        }
        if (subject.length() > MAX_SUBJECT) {
            return bad("Subject must be " + MAX_SUBJECT + " characters or fewer");
        }
        if (body.length() > MAX_BODY) {
            return bad("Body must be " + MAX_BODY + " characters or fewer");
        }

        TicketKind kind;
        try {
            kind = request.kind() == null
                    ? TicketKind.HELP
                    : TicketKind.valueOf(request.kind().toUpperCase());
        } catch (IllegalArgumentException e) {
            return bad("kind must be HELP or CHANGE");
        }

        Ticket ticket = tickets.save(new Ticket(require(authentication), subject, body, kind));
        return ResponseEntity.status(HttpStatus.CREATED).body(summary(ticket));
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public Map<String, Object> get(@PathVariable Long id, Authentication authentication) {
        Ticket ticket = owned(id, authentication);

        List<Map<String, Object>> comments = ticket.getComments().stream()
                .map(c -> Map.<String, Object>of(
                        "id", c.getId(),
                        "author", c.getAuthor().getUsername(),
                        "body", c.getBody(),
                        "createdAt", c.getCreatedAt()))
                .toList();

        Map<String, Object> detail = new java.util.LinkedHashMap<>(summary(ticket));
        detail.put("body", ticket.getBody());
        detail.put("comments", comments);
        return detail;
    }

    @PatchMapping("/{id}")
    @Transactional
    public ResponseEntity<?> updateStatus(@PathVariable Long id,
                                          @RequestBody StatusRequest request,
                                          Authentication authentication) {
        Ticket ticket = owned(id, authentication);

        TicketStatus status;
        try {
            status = TicketStatus.valueOf(String.valueOf(request.status()).toUpperCase());
        } catch (IllegalArgumentException e) {
            return bad("status must be OPEN, IN_PROGRESS or CLOSED");
        }

        ticket.setStatus(status);
        tickets.save(ticket);
        return ResponseEntity.ok(summary(ticket));
    }

    @PostMapping("/{id}/comments")
    @Transactional
    public ResponseEntity<?> comment(@PathVariable Long id,
                                     @RequestBody CommentRequest request,
                                     Authentication authentication) {
        String body = trimmed(request.body());
        if (body.isEmpty()) {
            return bad("Comment body is required");
        }
        if (body.length() > MAX_BODY) {
            return bad("Comment must be " + MAX_BODY + " characters or fewer");
        }

        User user = require(authentication);
        Ticket ticket = owned(id, authentication);
        ticket.addComment(new TicketComment(ticket, user, body));

        // Commenting on a closed ticket reopens it — otherwise the reply is invisible.
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            ticket.setStatus(TicketStatus.OPEN);
        }
        tickets.save(ticket);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "status", "comment added",
                "ticketStatus", ticket.getStatus(),
                "createdAt", Instant.now()
        ));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id, Authentication authentication) {
        tickets.delete(owned(id, authentication));
        return ResponseEntity.ok(Map.of("status", "ticket deleted"));
    }

    // -------------------------------------------------------------- helpers

    /** Reads {@code comments} lazily — only call this inside a transaction. */
    private static Map<String, Object> summary(Ticket ticket) {
        return Map.of(
                "id", ticket.getId(),
                "subject", ticket.getSubject(),
                "kind", ticket.getKind(),
                "status", ticket.getStatus(),
                "createdAt", ticket.getCreatedAt(),
                "updatedAt", ticket.getUpdatedAt(),
                "commentCount", ticket.getComments().size()
        );
    }

    private User require(Authentication authentication) {
        return users.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Account no longer exists"));
    }

    /** 404 rather than 403 for someone else's ticket — never confirm it exists. */
    private Ticket owned(Long id, Authentication authentication) {
        return tickets.findByIdAndOwner(id, require(authentication))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No such ticket"));
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static ResponseEntity<Map<String, String>> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    public record TicketRequest(String subject, String body, String kind) {}

    public record StatusRequest(String status) {}

    public record CommentRequest(String body) {}
}
