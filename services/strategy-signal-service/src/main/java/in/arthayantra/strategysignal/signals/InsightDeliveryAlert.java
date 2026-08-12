package in.arthayantra.strategysignal.signals;

import java.util.UUID;

/** Primitive-field delivery event consumed by notifier without creating an insights module cycle. */
public record InsightDeliveryAlert(
    UUID insightId,
    String title,
    String explanation,
    String scope,
    String channel) {}
