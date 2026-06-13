package in.arthayantra.strategysignal.registry;

import java.util.Locale;

/** Registry-owned semver triple: parse, compare, bump (the PUT auto patch-bump, C-2.10). */
public record SemVer(int major, int minor, int patch) implements Comparable<SemVer> {

  /** Bump kinds the PUT body may request. */
  public enum Bump {
    PATCH,
    MINOR,
    MAJOR;

    /** Parses the request value; null/blank defaults to PATCH. */
    public static Bump of(String value) {
      return value == null || value.isBlank()
          ? PATCH
          : Bump.valueOf(value.toUpperCase(Locale.ROOT));
    }
  }

  /** Parses {@code major.minor.patch}. */
  public static SemVer parse(String text) {
    String[] parts = text.split("\\.", -1);
    if (parts.length != 3) {
      throw new IllegalArgumentException("not a semver triple: " + text);
    }
    return new SemVer(
        Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
  }

  /** The bumped version. */
  public SemVer bump(Bump bump) {
    return switch (bump) {
      case PATCH -> new SemVer(major, minor, patch + 1);
      case MINOR -> new SemVer(major, minor + 1, 0);
      case MAJOR -> new SemVer(major + 1, 0, 0);
    };
  }

  @Override
  public int compareTo(SemVer other) {
    int byMajor = Integer.compare(major, other.major);
    if (byMajor != 0) {
      return byMajor;
    }
    int byMinor = Integer.compare(minor, other.minor);
    return byMinor != 0 ? byMinor : Integer.compare(patch, other.patch);
  }

  @Override
  public String toString() {
    return major + "." + minor + "." + patch;
  }
}
