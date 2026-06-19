/**
 * OpenAlgo unified-API wire DTOs — one record per REST response, mirroring every field OpenAlgo
 * documents (docs.openalgo.in /api/v1), not only the subset we currently consume. This package is
 * the OpenAlgo twin of {@code kite/wire} and follows the SAME anti-corruption convention so the
 * broker becomes swappable (Kite -> any of the 30+ brokers OpenAlgo fronts) with no change to the
 * domain ports. The gateways deserialize the raw wire into these with the shared Jackson
 * ObjectMapper, then map to the domain port records (QuoteGateway.Quote,
 * HistoricalCandleGateway.Candle).
 *
 * <p>AGPL boundary (license filter, master plan §1d / §2): OpenAlgo itself is an AGPL-3.0 appliance
 * consumed ONLY over its {@code /api/v1/} HTTP + WS surface — its source is NEVER merged here. These
 * DTOs are our own MIT code that describes that wire; the {@code in.openalgo:openalgo} Java SDK
 * (MIT) is reserved for the WS + order paths (plan §17.2). REST capture is hand-rolled
 * {@link org.springframework.web.client.RestClient} — NOT because the SDK's base URL is pinned (it
 * is settable, unlike Kite's), but for PARITY with the {@code kite/wire} pattern: typed
 * {@code @JsonIgnoreProperties} DTOs + off-critical-path drift detection (OpenAlgoContractCanary),
 * which the SDK's untyped {@code JsonObject} returns give up. WireMock can stand in for the
 * appliance in tests either way.
 *
 * <p>Convention for EVERY new OpenAlgo REST call (mirrors the Kite owner directive, 2026-06-19):
 * <ol>
 *   <li>Mirror the full response here — add a record with one component per documented field,
 *       skipping none even if unused. OpenAlgo's wire is the source of truth and the DTO is the
 *       ready reference.
 *   <li>Accept liberally: annotate each record {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 *       so a field OpenAlgo (or the fronted broker) adds can never crash the live feed. NEVER enable
 *       global {@code FAIL_ON_UNKNOWN_PROPERTIES} for the live mapper.
 *   <li>Bind, then map: the gateway calls {@code body(OpenAlgoXxx.class)} and maps the DTO to the
 *       domain record in place. These DTOs stay free of domain imports so the canary can reuse them.
 *   <li>Detect drift off the critical path: a field OpenAlgo renames/removes/retypes is caught by the
 *       daily {@link in.arthayantra.marketdata.openalgo.canary.OpenAlgoContractCanary} (against
 *       {@code openalgo-contract-manifest.json}) and by {@code OpenAlgoWireContractTest} in CI —
 *       never by a live deserialization failure.
 * </ol>
 *
 * <p>OpenAlgo greeks ({@code optiongreeks}) are mirror-only for drift detection (plan §17.9): no
 * production or replay path reads OpenAlgo greek <em>values</em> — IV/greeks come from
 * {@code libs/black76-math} so a network hop can never enter the deterministic replay.
 *
 * <p>snake_case wire names map to camelCase components via {@code @JsonProperty}; the shared mapper
 * is not configured for a snake_case naming strategy.
 */
package in.arthayantra.marketdata.openalgo.wire;
