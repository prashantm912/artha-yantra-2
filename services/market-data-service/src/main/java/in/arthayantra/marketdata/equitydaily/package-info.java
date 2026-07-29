/**
 * The split/bonus-ADJUSTED equity-daily read, shared by every plane that reads EQ daily bars.
 *
 * <p>⚠️ <b>This module must stay a LEAF — zero outgoing {@code in.arthayantra.marketdata} imports.</b>
 * That constraint is the whole reason it exists, and it is what makes it safe for any module to
 * depend on.
 *
 * <p>It was carved out of {@code screener} on 2026-07-29 to break a Modulith cycle. #1094 correctly
 * consolidated four hand-rolled {@code nse_eod_bhavcopy} reads onto ONE definition of the
 * CA-adjustment factor, but the single copy lived in {@code screener}, so {@code nse} had to import
 * {@code screener} to use it — closing {@code bhavcopy → nse → screener → bhavcopy}. The other two
 * edges are structural and correct ({@code BhavcopyBackfillService} genuinely orchestrates the NSE
 * fetchers; the Manas/Minervini schedulers genuinely listen for {@code BhavcopyBackfillCompleted}),
 * so the newest edge is the one that had to go.
 *
 * <p>Moving the definition rather than duplicating it keeps #1094's goal intact: still exactly one
 * definition of the adjustment factor, now in a package both consumers can depend on without either
 * depending on the other.
 *
 * <p>⚠️ The cycle was live on main for hours while CI reported green — surefire's
 * {@code rerunFailingTestsCount=2} filed the deterministic {@code ModularityTest} failure as a
 * flake. Fixed separately; if a future change re-points this module at another one, the build will
 * now fail honestly rather than retry green.
 */
package in.arthayantra.marketdata.equitydaily;
