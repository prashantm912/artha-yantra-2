package in.arthayantra.strategysignal.manas;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import in.arthayantra.strategysignal.swing.SwingBatchRecorder;
import org.junit.jupiter.api.Test;

/**
 * The Manas swing scheduler is now a thin shell — it just delegates to the shared {@link
 * SwingBatchRecorder#runScheduled} with its doctrine (the recorder owns the marker + the FAILED-alert
 * envelope, covered by {@code SwingBatchRecorderTest}).
 */
class ManasAroraSwingSchedulerTest {

  @Test
  void delegatesToTheRecorderWithTheManasDoctrine() {
    SwingBatchRecorder recorder = mock(SwingBatchRecorder.class);
    ManasDoctrine doctrine = mock(ManasDoctrine.class);

    new ManasAroraSwingScheduler(recorder, doctrine).run();

    verify(recorder).runScheduled(doctrine);
  }
}
