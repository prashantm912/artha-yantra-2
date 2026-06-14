package in.arthayantra.strategysignal.signals;

import java.math.BigDecimal;

/**
 * Published when the owner marks a signal TAKEN. The paper module listens and OPTIONALLY opens a
 * paper position (when a qty is supplied) — an event keeps the signals module free of any compile
 * dependency on paper, so the module graph stays acyclic.
 */
public record SignalTaken(long signalId, Integer qty, BigDecimal fillPrice) {}
