package com.nimbus.api.status;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public uptime/health surface for the status page.
 *
 * Deliberately terse: it reports whether things work, never which versions or
 * hosts are behind them. Anything richer belongs on the actuator, which nginx
 * refuses to proxy.
 */
@RestController
public class StatusController {

    private final DataSource dataSource;
    private final Instant startedAt = Instant.now();

    public StatusController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        boolean databaseUp = databaseReachable();
        Instant now = Instant.now();
        Duration uptime = Duration.between(startedAt, now);

        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("api", "up");
        checks.put("database", databaseUp ? "up" : "down");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", databaseUp ? "operational" : "degraded");
        body.put("checks", checks);
        body.put("startedAt", startedAt);
        body.put("serverTime", now);
        body.put("uptimeSeconds", uptime.toSeconds());
        body.put("uptime", humanize(uptime));

        // A degraded stack must not answer 200 — uptime monitors key off the code.
        return ResponseEntity
                .status(databaseUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(body);
    }

    private boolean databaseReachable() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    private static String humanize(Duration uptime) {
        long days = uptime.toDays();
        long hours = uptime.toHoursPart();
        long minutes = uptime.toMinutesPart();
        long seconds = uptime.toSecondsPart();

        if (days > 0)   return "%dd %dh %dm".formatted(days, hours, minutes);
        if (hours > 0)  return "%dh %dm".formatted(hours, minutes);
        if (minutes > 0) return "%dm %ds".formatted(minutes, seconds);
        return "%ds".formatted(seconds);
    }
}
