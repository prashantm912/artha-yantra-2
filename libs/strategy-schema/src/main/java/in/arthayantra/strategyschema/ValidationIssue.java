package in.arthayantra.strategyschema;

/** One pointer-anchored validation finding ({@code path} is a JSON pointer into the config). */
public record ValidationIssue(String path, String message) {}
