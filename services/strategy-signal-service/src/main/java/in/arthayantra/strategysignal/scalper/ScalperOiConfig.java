package in.arthayantra.strategysignal.scalper;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link ScalperOiProps} so the {@code artha.scalper.oi.*} block binds in every profile
 * (mock/live/tests) — the {@link ScalperConfluenceGate} component injects the bound instance and
 * hands it to the pure {@code ConnectTheDotsScorer} and the #5 pre-gate.
 */
@Configuration
@EnableConfigurationProperties(ScalperOiProps.class)
public class ScalperOiConfig {}
