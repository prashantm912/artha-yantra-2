package in.arthayantra.strategysignal.registry;

import java.util.List;

/** Shared BTST exit-rule allowlists used by the publish gate and its classpath boundary test. */
public final class BtstExitRuleParityRules {

  /** Exit-rule types that read only parity-stable entry/session data on the BTST path. */
  public static final List<String> SAFE_TYPES =
      List.of("time_stop", "stop_loss", "take_profit");

  /** Level bases that do not read divergent historical daily bars on the BTST path. */
  public static final List<String> SAFE_LEVEL_BASES = List.of("premium_pct", "percent");

  private BtstExitRuleParityRules() {}
}
