package in.arthayantra.backtest.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/** One owner-scoped named filter set; {@code filter} is opaque to the backend. */
public record SavedView(
    String id, String kind, String name, JsonNode filter, OffsetDateTime createdAt) {}
