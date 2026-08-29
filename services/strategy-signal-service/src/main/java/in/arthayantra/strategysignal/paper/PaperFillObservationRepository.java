package in.arthayantra.strategysignal.paper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * H44: the durable ledger of paper fills struck on a contract that has NEVER ticked (V065).
 *
 * <p><b>Why this exists at all.</b> The arming decision for
 * {@code artha.paper.refuse-no-tick-entries} was to rest on a measured weekly rate. That rate was
 * being read from {@code ay_paper_fill_no_tick_total}, a Micrometer counter — process-lifetime, and
 * reset on every restart. Measured 2026-08-29 on the weekly report's FIRST run: the container had
 * restarted 11 minutes earlier, so a "weekly" figure covered 11 minutes of a Saturday, and this stack
 * restarted three times in 24 hours. <b>A weekly rate keyed on that counter can only ever mean "since
 * the last restart", which is indistinguishable from a genuinely quiet week</b> — it fails in the
 * reassuring direction, which is the whole reason H44 was expensive in the first place.
 *
 * <p><b>Deliberately NOT {@code paper_order_rejections}</b>, which was the first plan and was wrong.
 * That table calls itself "the append-only ledger of REFUSED paper-order..." and is served through a
 * filtered read surface; these rows are the opposite — the fill SUCCEEDED. Putting them there would
 * show a successful trade in an operator's refusals listing and inflate any count that does not
 * happen to filter on {@code reason}.
 *
 * <p>REQUIRES_NEW for the same reason every forensic writer here uses it: this runs after the trade
 * is durable and must never be able to affect it.
 */
@Repository
public class PaperFillObservationRepository {

  private final JdbcTemplate jdbc;

  public PaperFillObservationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Records one no-tick fill. {@code book} may be null on an unattributed hand ticket. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(
      long positionId, String book, String exchange, String tradingsymbol, long qty) {
    jdbc.update(
        "INSERT INTO paper_fill_observations"
            + " (position_id, book, exchange, tradingsymbol, qty) VALUES (?, ?, ?, ?, ?)",
        positionId,
        book,
        exchange,
        tradingsymbol,
        qty);
  }
}
