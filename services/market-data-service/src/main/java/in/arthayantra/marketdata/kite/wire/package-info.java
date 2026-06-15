/**
 * Kite Connect v3 wire DTOs — one record per REST response, mirroring <b>every</b> field Kite
 * documents (<a href="https://kite.trade/docs/connect/v3">kite.trade/docs/connect/v3</a>), not only
 * the subset we currently consume. The live gateways deserialize the raw wire into these with the
 * shared Jackson {@code ObjectMapper}, then map to the domain port records
 * ({@code QuoteGateway.Quote}, {@code HistoricalCandleGateway.Candle},
 * {@code InstrumentDumpGateway.InstrumentRecord}, {@code SessionWireClient.TokenSession}).
 *
 * <h2>Convention for EVERY new Kite REST call (owner directive, 2026-06-15)</h2>
 * <ol>
 *   <li><b>Mirror the full response here</b> — add a record with one component per documented field,
 *       skipping none even if unused. Kite's wire is the source of truth and the DTO is the ready
 *       reference for the day we want a field.</li>
 *   <li><b>Accept liberally:</b> annotate each record {@code @JsonIgnoreProperties(ignoreUnknown =
 *       true)} so a field Kite <i>adds</i> can never crash the live feed. NEVER enable the global
 *       {@code FAIL_ON_UNKNOWN_PROPERTIES} for the live mapper — that couples the live path to
 *       Kite's exact wire shape and breaks on any additive change.</li>
 *   <li><b>Bind, then map:</b> the gateway calls {@code body(KiteXxx.class)} and maps the DTO to the
 *       domain record in place. These DTOs stay free of domain imports so the canary can reuse
 *       them.</li>
 *   <li><b>Detect drift off the critical path:</b> a field Kite renames/removes/retypes is caught by
 *       the daily {@link in.arthayantra.marketdata.kite.canary.ContractCanary} (against
 *       {@code kite-contract-manifest.json}) and by {@code KiteWireContractTest} in CI — never by a
 *       live deserialization failure.</li>
 * </ol>
 *
 * <p>Snake_case wire names map to camelCase components via {@code @JsonProperty}; the shared mapper
 * is not configured for a snake_case naming strategy.
 */
package in.arthayantra.marketdata.kite.wire;
