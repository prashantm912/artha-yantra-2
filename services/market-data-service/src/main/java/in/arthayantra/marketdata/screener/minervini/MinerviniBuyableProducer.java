package in.arthayantra.marketdata.screener.minervini;

import in.arthayantra.marketdata.alerts.NtfyClient;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase-9 (MV-9.4 net-new): pushes an ntfy alert when a SEPA-funnel candidate TRANSITIONS into the
 * immediately-buyable band — a name that was not buyable on the prior screen but is today (its price
 * reached the VCP pivot). Runs post-screen (the 19:30 geometry/funnel refresh) and diffs today's
 * buyable set against the previous screen date's. Gated on {@code
 * artha.minervini.buyable-alerts.enabled} (default off) — the owner turns it on with the swing batch.
 */
@Component
@ConditionalOnProperty(value = "artha.minervini.buyable-alerts.enabled", havingValue = "true")
public class MinerviniBuyableProducer {

  private static final Logger log = LoggerFactory.getLogger(MinerviniBuyableProducer.class);
  private static final int MAX_NAMED = 15; // cap the names in the push body; the count is always exact

  private final MinerviniScreenRepository screenRepo;
  private final MinerviniFunnelService funnel;
  private final NtfyClient ntfy;

  /** Wires the screen repo, the funnel, and the ntfy client. */
  public MinerviniBuyableProducer(
      MinerviniScreenRepository screenRepo, MinerviniFunnelService funnel, NtfyClient ntfy) {
    this.screenRepo = screenRepo;
    this.funnel = funnel;
    this.ntfy = ntfy;
  }

  /** Post-screen daily run (19:45 IST, weekdays) — one aggregate push naming the newly-buyable set. */
  @Scheduled(cron = "${artha.minervini.buyable-alerts.cron:0 45 19 * * MON-FRI}", zone = "Asia/Kolkata")
  public void alertNewlyBuyable() {
    try {
      List<LocalDate> dates = screenRepo.recentScreenDates(2);
      if (dates.size() < 2) {
        return; // no prior screen to diff against (first run) — nothing to compare
      }
      Set<String> today = buyableSymbols(dates.get(0));
      Set<String> prior = buyableSymbols(dates.get(1));
      Set<String> fresh = new LinkedHashSet<>(today);
      fresh.removeAll(prior);
      if (fresh.isEmpty()) {
        return;
      }
      String names =
          fresh.stream().limit(MAX_NAMED).collect(Collectors.joining(", "))
              + (fresh.size() > MAX_NAMED ? ", …" : "");
      ntfy.send(
          "Minervini: " + fresh.size() + " name(s) now buyable",
          "default",
          names + " — reached the VCP pivot (screen " + dates.get(0) + ")");
      log.info("minervini buyable alert: {} newly buyable on {}", fresh.size(), dates.get(0));
    } catch (RuntimeException e) {
      log.warn("minervini buyable alert skipped: {}", e.getMessage());
    }
  }

  private Set<String> buyableSymbols(LocalDate date) {
    return funnel.funnel(date).immediatelyBuyable().stream()
        .map(MinerviniFunnelService.FunnelRow::symbol)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }
}
