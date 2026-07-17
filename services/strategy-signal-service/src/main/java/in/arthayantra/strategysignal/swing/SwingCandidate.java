package in.arthayantra.strategysignal.swing;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * A family-neutral funnel candidate handed from a {@link SwingDoctrine} to the {@link
 * SwingBatchEngine}. The engine never sees a family's own candidate type — the doctrine normalizes
 * everything the engine needs to score and emit:
 *
 * <ul>
 *   <li>{@code symbol} — the tradingsymbol to fetch a daily series for and score.
 *   <li>{@code contextSeeds} — the flat indicator-context series to seed (name → pivot value). The
 *       engine seeds one flat context bar per entry (the same mechanism the golden runner uses).
 *       Minervini always supplies its three (0-filled when a pivot is null, matching the old
 *       unconditional seed); Manas supplies only the non-null of its three.
 *   <li>{@code eligibleSetups} — which setup tokens this candidate is a valid base for; {@code null}
 *       means "all" (no setup routing — the Minervini case). Manas supplies the setups whose pivot is
 *       present so the breakout strategy only fires on a breakout base and the VCP strategy on a VCP
 *       base (the old {@code pivotFor} null-skip, expressed as data).
 *   <li>{@code detailBase} — the CANDIDATE-derived fields of the family {@code *_detail} side-channel
 *       JSON (footprint / pivot / stage / setupType / …), in insertion order, MINUS the leading
 *       {@code setup} (the emitting strategy's slug, which only the engine knows at emit) and MINUS
 *       any {@code pyramidLot} (engine-owned). The engine writes {@code {setup, ...detailBase,
 *       pyramidLot?}} so the persisted JSON is byte-identical to the old per-family writer.
 *   <li>{@code onDeck} — which funnel bucket the candidate came from ({@code false} =
 *       immediately-buyable, {@code true} = on-deck). The engine admits an on-deck candidate only for
 *       a strategy whose {@code universe.bucket} includes on-deck, which is what makes the leaf mean
 *       the same thing live as it does in the submission pin.
 * </ul>
 */
public record SwingCandidate(
    String symbol,
    Map<String, BigDecimal> contextSeeds,
    Set<String> eligibleSetups,
    ObjectNode detailBase,
    boolean onDeck) {}
