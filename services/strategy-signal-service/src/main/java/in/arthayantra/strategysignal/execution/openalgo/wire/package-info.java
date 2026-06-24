/**
 * OpenAlgo account/order-read wire DTOs — one record per {@code /api/v1} response (orderbook,
 * tradebook, positionbook, funds), mirroring every field OpenAlgo documents (docs.openalgo.in
 * accounts-api), not only the subset we currently consume. This package is the strategy-signal twin
 * of {@code market-data-service}'s {@code openalgo/wire} package and follows the SAME
 * anti-corruption convention (which itself mirrors {@code kite/wire}) so the broker becomes
 * swappable (Kite -&gt; any of the 30+ brokers OpenAlgo fronts) with no change to the domain
 * {@link in.arthayantra.strategysignal.execution.OrderGateway} port records.
 *
 * <p>These are the §18.1 ORDER-READ surface (orderbook / positions / tradebook / funds) — the
 * documented Phase-4b read deliverable. The order-WRITE path stays gated behind
 * {@link in.arthayantra.strategysignal.execution.OrderGateway#place} and is untouched here.
 *
 * <p>AGPL boundary (license filter, master plan §1d / §2): OpenAlgo itself is an AGPL-3.0 appliance
 * consumed ONLY over its {@code /api/v1/} HTTP surface — its source is NEVER merged here. These DTOs
 * are our own MIT code that describes that wire. REST capture is the hand-rolled
 * {@link org.springframework.web.client.RestClient} (NOT the {@code in.openalgo:openalgo} SDK) for
 * PARITY with the {@code kite/wire} pattern: typed {@code @JsonIgnoreProperties} DTOs that survive
 * additive wire changes, where the SDK's untyped {@code JsonObject} returns give up the type guard.
 *
 * <p>Convention for EVERY new OpenAlgo REST call (mirrors the owner directive, 2026-06-19):
 * <ol>
 *   <li>Mirror the full response here — one record component per documented field, skipping none
 *       even if unused. OpenAlgo's wire is the source of truth and the DTO is the ready reference.
 *   <li>Accept liberally: each record is {@code @JsonIgnoreProperties(ignoreUnknown = true)} so a
 *       field OpenAlgo (or the fronted broker) adds can never crash the read. NEVER enable global
 *       {@code FAIL_ON_UNKNOWN_PROPERTIES} on the live mapper.
 *   <li>Bind, then map: the gateway calls {@code body(OpenAlgoXxxResponse.class)} and maps the DTO
 *       to the domain record in place. These DTOs stay free of domain imports.
 *   <li>Detect drift off the critical path: {@code OpenAlgoOrderWireContractTest} round-trips a full
 *       fixture into each record (a dropped/mis-annotated field surfaces as a null assertion) and
 *       proves an UNKNOWN field never throws — never a live deserialization failure.
 * </ol>
 *
 * <p>Wire types per the docs.openalgo.in samples: orderbook {@code price}/{@code trigger_price} and
 * tradebook {@code average_price}/{@code trade_value} are JSON NUMBERS; positionbook
 * {@code average_price} and ALL funds fields are STRING decimals. Each maps to {@code BigDecimal}
 * (Jackson coerces both number and string into BigDecimal), so the exact-decimal contract holds
 * regardless of which the fronted broker emits. snake_case wire names map to camelCase components
 * via {@code @JsonProperty}; the shared mapper is not configured for a snake_case strategy.
 */
package in.arthayantra.strategysignal.execution.openalgo.wire;
