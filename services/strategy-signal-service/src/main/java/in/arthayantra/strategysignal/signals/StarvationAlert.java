package in.arthayantra.strategysignal.signals;

/** In-process signal-starvation alert event consumed by the notifier module. */
public record StarvationAlert(String title, String message) {}
