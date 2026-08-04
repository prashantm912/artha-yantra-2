package in.arthayantra.gateway.routes;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Runbook-tree guard. The repo carries TWO skill trees — {@code .claude/skills/} (24 skills, read
 * by Claude sessions) and {@code .agents/skills/} (14 skills, read by Codex sessions) — and a
 * correction applied to one has repeatedly failed to reach the other. These runbooks load into
 * every delegated builder session, so a stale instruction there is not documentation rot; it is an
 * agent being actively told to do the wrong thing.
 *
 * <p><b>⚠️ THE TREES ARE NOT BYTE-IDENTICAL, AND MUST NOT BE ASSERTED TO BE.</b> Measured
 * 2026-08-04: of the 14 skills present in both trees, 9 are byte-identical and 5 differ, and the
 * differences are a MIX of deliberate and stale. Deliberate: each tree names its own host doc
 * ({@code AGENTS.md} vs {@code CLAUDE.md}) and its own scheduled-task owner ("scheduled Codex task"
 * vs "scheduled Claude task"); {@code .claude}'s copies additionally carry Claude-only sections
 * that would be circular in the Codex tree (the Fable §2a plan gate, and a cross-vendor gate whose
 * whole instruction is "call {@code codex-code-review}, the OPPOSITE vendor"). Stale: {@code
 * .agents} is missing owner revisions that landed in {@code .claude} weeks ago. A blanket
 * byte-comparison would therefore assert something FALSE, redden on the first legitimate
 * vendor-specific edit, and be deleted or {@code @Disabled} within a week — and a disabled guard is
 * worse than none, because it reads as coverage. So the invariant here is deliberately narrower and
 * true.
 *
 * <p><b>Scope: the INTERSECTION of the two trees, {@code SKILL.md} only.</b> Ten skills exist only
 * under {@code .claude} ({@code codex-*}, {@code claude-review}, {@code comprehensive-audit},
 * {@code hotfix}, {@code research}, {@code write-tests}) — most are Codex-lane machinery that would
 * be self-referential in the Codex tree. There is nothing for them to drift AGAINST, so requiring
 * every Claude skill to have an {@code .agents} twin would assert a second false invariant. Only
 * {@code SKILL.md} is compared because that is the only file type {@code .agents/skills} contains;
 * {@code .claude}'s {@code prompts/}, {@code scripts/} and {@code state/} have no counterpart.
 *
 * <p><b>⚠️ This guard is UNREACHABLE unless the ci-java classifier routes the skill trees to this
 * shard.</b> {@code .github/scripts/classify_java_shards.sh} maps changed paths to {@code
 * build-test} shards, and a path it does not match classifies to NO shard — the required context
 * then reports green in ~2 s without running anything. A skills-only PR is precisely the edit this
 * test exists to catch, so the classifier carries an explicit {@code .claude|.agents/skills/} rule
 * routed here, pinned by an assertion in {@code classify_java_shards_test.sh}. That is the same
 * hole that made {@code TrialMetricsCatalogConsistencyTest} unreachable from a catalog-only edit
 * (#1246); do not remove the rule without deleting this test.
 *
 * <p>Same pure-file pattern as {@link MapReturnRatchetTest} — walks the repo root, no containers,
 * rides the ci-java strategy-gateway shard.
 */
class SkillTreeSyncTest {

  private static final Path CLAUDE_TREE = Path.of(".claude", "skills");
  private static final Path AGENTS_TREE = Path.of(".agents", "skills");

  /**
   * Pairs that legitimately differ, with the reason. EXACT, not a floor: a pair listed here that
   * becomes byte-identical ALSO fails, so the list cannot quietly accumulate dead entries and stop
   * meaning anything. Adding a name here is a deliberate act — it says "these two copies are
   * allowed to say different things", which is exactly the decision that should be visible in a
   * diff rather than made by silence.
   *
   * <p>Measured 2026-08-04, each verified by reading the diff rather than assumed:
   *
   * <ul>
   *   <li>{@code daily-ops} — 2 lines, both the scheduled-task owner label ("scheduled Codex task"
   *       vs "scheduled Claude task"). Deliberate; nothing stale.
   *   <li>{@code delegated-ship} — {@code AGENTS.md} vs {@code CLAUDE.md} (deliberate) plus four
   *       blocks {@code .claude} carries and {@code .agents} does not: the 2026-07-25 model-routing
   *       revision, batched-build mode, the cross-vendor gate, and the PR-verdict shape. The last
   *       two are Claude-side by construction (they instruct an Anthropic builder to fetch a Codex
   *       reviewer); the routing revision is genuine {@code .agents} staleness.
   *   <li>{@code fable-method} — {@code AGENTS.md} vs {@code CLAUDE.md} ×2 (deliberate) plus the
   *       §2a Fable plan gate, which {@code .agents} lacks (staleness).
   *   <li>{@code session-analysis} — {@code .claude} carries the {@code open} market-open liveness
   *       mode and the G15 session-regime row; {@code .agents} predates both (staleness).
   *   <li>{@code ship-a-change} — {@code .claude} carries the PR cross-vendor-verdict block
   *       (staleness in {@code .agents}).
   * </ul>
   *
   * <p>Four of the five carry real staleness. This test does NOT fix that — back-porting content
   * between vendor trees is a content decision, not a mechanical sync, and doing it silently inside
   * a guard would be the "too strong" failure. What the test does is stop the set from GROWING
   * without someone saying so.
   */
  private static final Set<String> KNOWN_DIVERGENT =
      Set.of("daily-ops", "delegated-ship", "fable-method", "session-analysis", "ship-a-change");

  /**
   * The prescription shape, not the word. {@code --admin} legitimately appears in PROSE in both
   * trees — the corrected {@code delegated-ship} bullet says "⚠️ {@code --admin} was REMOVED here
   * 2026-08-04" — and banning the token would punish the very correction this guard exists to
   * protect, which is how a guard gets deleted.
   *
   * <p>⚠️ The {@code [^`\n]*} is load-bearing twice over. Excluding the NEWLINE keeps a merge
   * command from matching an unrelated {@code --admin} paragraphs later. Excluding the BACKTICK is
   * what separates a copy-pasteable command from prose about one: inside a code span there is no
   * backtick between {@code gh pr merge} and {@code --admin}, whereas the corrected form
   * — {@code `gh pr merge <n> --squash`} followed by prose {@code `--admin`} — closes the span in
   * between, so the character class stops there. Both shapes were measured against the real files
   * before this pattern was chosen.
   *
   * <p>KNOWN LIMIT, stated rather than papered over: a prescription split across lines, or the
   * bare-prose spelling "admin-merge", is NOT caught. The prose spelling was deliberately left out
   * because banning it would also redden the legitimate cautionary use ("do not reflexively
   * admin-merge a known flake") that CLAUDE.md itself prescribes — the classic too-strong guard.
   * This pattern catches the copy-pasteable command, which is the form an agent actually executes.
   */
  private static final Pattern ADMIN_MERGE_PRESCRIPTION =
      Pattern.compile("gh pr merge[^`\\n]*--admin");

  /**
   * {@code hotfix} is EXEMPT BY DESIGN, not by oversight. CLAUDE.md's merge rule ends "{@code
   * hotfix/*} keeps its own fast-lane", and {@code ci-review-verdict.yml} exempts the same branch
   * prefix. A live-incident lane that cannot merge past an unrelated red check is not a fast lane.
   * It exists only under {@code .claude}, so this is a one-tree exemption.
   */
  private static final Set<String> ADMIN_MERGE_EXEMPT_SKILLS = Set.of("hotfix");

  @Test
  void sharedRunbooksAreInSyncUnlessDivergenceIsDeclared() throws IOException {
    Path repoRoot = findRepoRoot();
    List<String> problems = new ArrayList<>();

    Set<String> shared = sharedSkillNames(repoRoot);
    assertThat(shared)
        .withFailMessage(
            "No skill name is present in BOTH %s and %s. Either a tree moved or this test is"
                + " comparing nothing — a guard that silently compares an empty set is the"
                + " failure mode it exists to prevent.",
            CLAUDE_TREE, AGENTS_TREE)
        .isNotEmpty();

    for (String skill : shared) {
      byte[] claude = Files.readAllBytes(skillFile(repoRoot, CLAUDE_TREE, skill));
      byte[] agents = Files.readAllBytes(skillFile(repoRoot, AGENTS_TREE, skill));
      boolean identical = java.util.Arrays.equals(claude, agents);
      boolean declared = KNOWN_DIVERGENT.contains(skill);

      if (!identical && !declared) {
        problems.add(
            ("'%s' now differs between the two runbook trees but is not declared divergent."
                    + " These files load into agent sessions, so a correction that reaches only"
                    + " one tree leaves the other actively instructing agents wrongly. Either"
                    + " mirror the edit into both copies, or — if the difference is deliberately"
                    + " vendor-specific — add '%s' to KNOWN_DIVERGENT with the reason.")
                .formatted(skill, skill));
      }
      if (identical && declared) {
        problems.add(
            ("'%s' is listed in KNOWN_DIVERGENT but the two copies are now byte-identical."
                    + " Remove it from the list: an allowlist that keeps entries after they stop"
                    + " applying stops describing anything and licenses real drift later.")
                .formatted(skill));
      }
    }

    assertThat(problems).withFailMessage("%s", String.join("\n", problems)).isEmpty();
  }

  @Test
  void noRunbookPrescribesAdminMergeOutsideTheHotfixFastLane() throws IOException {
    Path repoRoot = findRepoRoot();
    List<String> offenders = new ArrayList<>();

    for (Path tree : List.of(CLAUDE_TREE, AGENTS_TREE)) {
      Path treeRoot = repoRoot.resolve(tree);
      if (!Files.isDirectory(treeRoot)) {
        continue;
      }
      try (Stream<Path> files = Files.walk(treeRoot)) {
        for (Path file : files.filter(f -> f.getFileName().toString().equals("SKILL.md")).toList()) {
          String skill = file.getParent().getFileName().toString();
          if (ADMIN_MERGE_EXEMPT_SKILLS.contains(skill)) {
            continue;
          }
          String body = Files.readString(file);
          if (ADMIN_MERGE_PRESCRIPTION.matcher(body).find()) {
            offenders.add(repoRoot.relativize(file).toString().replace('\\', '/'));
          }
        }
      }
    }

    assertThat(offenders)
        .withFailMessage(
            "These runbooks prescribe `gh pr merge ... --admin` as a merge path: %s."
                + " `--admin` bypasses ALL EIGHT of main's required contexts, and `lock_branch`"
                + " — the branch-level lock that was the only reason it was ever needed — was set"
                + " false on 2026-07-26. Reaching for it now means a required check is genuinely"
                + " red, and the correct action is to read that check. Only the `hotfix` skill is"
                + " exempt (CLAUDE.md: `hotfix/*` keeps its own fast-lane). Referring to `--admin`"
                + " in PROSE is fine and does not trip this — only the executable command shape"
                + " does.",
            offenders)
        .isEmpty();
  }

  private static Set<String> sharedSkillNames(Path repoRoot) throws IOException {
    Set<String> shared = new TreeSet<>();
    Path claudeRoot = repoRoot.resolve(CLAUDE_TREE);
    Path agentsRoot = repoRoot.resolve(AGENTS_TREE);
    try (Stream<Path> dirs = Files.list(claudeRoot)) {
      for (Path dir : dirs.filter(Files::isDirectory).toList()) {
        String name = dir.getFileName().toString();
        if (Files.isRegularFile(dir.resolve("SKILL.md"))
            && Files.isRegularFile(agentsRoot.resolve(name).resolve("SKILL.md"))) {
          shared.add(name);
        }
      }
    }
    return shared;
  }

  private static Path skillFile(Path repoRoot, Path tree, String skill) {
    return repoRoot.resolve(tree).resolve(skill).resolve("SKILL.md");
  }

  private static Path findRepoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve("contracts"))) {
      dir = dir.getParent();
    }
    assertThat(dir).as("repo root (contracts/ dir) above the module dir").isNotNull();
    return dir;
  }
}
