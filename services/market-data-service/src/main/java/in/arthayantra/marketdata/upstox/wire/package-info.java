/**
 * Upstox v2 wire DTOs (ADR-0002 anti-corruption mirror — the SAME convention as {@code kite/wire}).
 * One record per REST response, mirroring <b>every</b> documented field of the live Upstox endpoint —
 * not only the subset we currently consume — so the wire DTO is the ready reference for the day we
 * want a field. The hand-rolled {@code RestClient} gateways {@code body(UpstoxXxx.class)} then map to
 * the domain port records; these DTOs stay free of domain imports.
 *
 * <h2>Convention for EVERY new Upstox REST call (owner directive)</h2>
 * <ol>
 *   <li><b>Mirror the full response here</b> — a record with one component per documented field,
 *       skipping none even if unused.</li>
 *   <li><b>Accept liberally:</b> annotate each record {@code @JsonIgnoreProperties(ignoreUnknown =
 *       true)} so a field Upstox <i>adds</i> can never crash the live feed. NEVER enable the global
 *       {@code FAIL_ON_UNKNOWN_PROPERTIES} for the live mapper.</li>
 *   <li><b>Bind, then map:</b> the gateway maps the DTO to the domain record in place.</li>
 *   <li><b>Detect drift off the critical path:</b> a renamed/removed/retyped field is caught by the
 *       {@code UpstoxOptionChainWireContractTest} (consumed-field manifest) in CI — never by a live
 *       deserialization failure.</li>
 * </ol>
 *
 * <p>Snake_case wire names map to camelCase components via {@code @JsonProperty}; the shared mapper is
 * not configured for a snake_case naming strategy.
 */
package in.arthayantra.marketdata.upstox.wire;
