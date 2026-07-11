package in.arthayantra.strategysignal.insights;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the {@code artha.insights.*} config subtree (INT design §2.5 / §3.2). */
@Configuration
@EnableConfigurationProperties(InsightProperties.class)
public class InsightConfig {}
