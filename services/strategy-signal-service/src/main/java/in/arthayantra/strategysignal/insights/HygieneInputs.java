package in.arthayantra.strategysignal.insights;

/**
 * The journal-hygiene snapshot the HYGIENE generator consumes (INT design §2.2). Assembled at the
 * engine edge by {@link PortfolioReader} from {@code journal_entries} + {@code paper_positions} over
 * a trailing window — display-only nudges, never a mutation.
 *
 * @param windowDays the trailing window the counts cover (e.g. 7)
 * @param unratedAutoEntries journal entries in-window with no discipline/emotion rating
 * @param closedPositionsWithoutJournal closed positions in-window with no linked journal entry
 */
public record HygieneInputs(
    int windowDays, int unratedAutoEntries, int closedPositionsWithoutJournal) {}
