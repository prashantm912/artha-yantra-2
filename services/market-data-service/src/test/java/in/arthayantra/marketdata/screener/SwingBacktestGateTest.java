package in.arthayantra.marketdata.screener;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Audit M25: the single-permit bulkhead admits exactly one deep swing backtest at a time. */
class SwingBacktestGateTest {

  @Test
  void onlyOnePermitIsHeldAtATime() {
    SwingBacktestGate gate = new SwingBacktestGate();
    assertThat(gate.tryAcquire()).as("first sim takes the permit").isTrue();
    assertThat(gate.tryAcquire()).as("the second family is refused while one holds it").isFalse();
    gate.release();
    assertThat(gate.tryAcquire()).as("permit is reusable once released").isTrue();
    gate.release();
  }
}
