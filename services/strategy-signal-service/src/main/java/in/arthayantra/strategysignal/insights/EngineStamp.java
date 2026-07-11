package in.arthayantra.strategysignal.insights;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Component;

/**
 * The engine identity stamped onto every insight row (INT design §9.2 traceability): the generating
 * jar's git commit SHA ({@code engine_version}, resolved once from {@code git.properties} — the H9
 * #617 mechanism, fail-soft to {@code "dev"} when the property file is absent, e.g. a source-tarball
 * or test jar) and the {@code config_hash} = SHA-256 of the resolved {@code artha.insights.*} subtree.
 * Changing a weight/threshold changes the hash, so a config change is visible in every insight it
 * produced — the frozen-config-in-the-row property that makes replays auditable.
 */
@Component
public class EngineStamp {

  private static final Logger log = LoggerFactory.getLogger(EngineStamp.class);

  private final String engineVersion;
  private final String configHash;

  private EngineStamp(String engineVersion, String configHash) {
    this.engineVersion = engineVersion;
    this.configHash = configHash;
  }

  /** Production wiring: git SHA (or {@code "dev"}) + SHA-256 of the resolved config. */
  @Autowired
  public EngineStamp(
      ObjectProvider<GitProperties> git, InsightProperties props, ObjectMapper objectMapper) {
    this(shaOf(git.getIfAvailable()), hashConfig(props, objectMapper));
  }

  /** Test/explicit factory — pins both values directly. */
  public static EngineStamp of(String engineVersion, String configHash) {
    return new EngineStamp(engineVersion, configHash);
  }

  /** The git commit SHA of this jar, or {@code "dev"} when {@code git.properties} is absent. */
  public String engineVersion() {
    return engineVersion;
  }

  /** The SHA-256 hex of the resolved {@code artha.insights.*} config in force. */
  public String configHash() {
    return configHash;
  }

  private static String shaOf(GitProperties git) {
    return git == null ? "dev" : git.getCommitId();
  }

  private static String hashConfig(InsightProperties props, ObjectMapper objectMapper) {
    try {
      // Canonical serialization (map keys sorted, record components in declaration order) so the
      // hash is stable across restarts for an unchanged config.
      ObjectMapper canonical =
          objectMapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
      byte[] json = canonical.writeValueAsBytes(props);
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(json));
    } catch (Exception e) {
      // A hash failure must never break insight generation — degrade to a stable sentinel.
      log.warn("insight config hash failed — using sentinel: {}", e.getMessage());
      return "unhashed";
    }
  }
}
