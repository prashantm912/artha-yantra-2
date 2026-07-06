package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins the in-memory book-precedence copy (audit H12 / M31: this precedence is encoded three times —
 * here, in {@code BookResolver.bookForSignal}'s SQL CASE, and in the signals page's tag filter — and
 * they must agree). The book is the strategy's FIRST recognised family tag; order is
 * scalper &gt; minervini &gt; manas-arora &gt; else other.
 */
class BooksTest {

  @Test
  void singleFamilyTagMapsToItsBook() {
    assertThat(Books.fromTags(List.of("scalper"))).isEqualTo(Books.SCALPER);
    assertThat(Books.fromTags(List.of("minervini"))).isEqualTo(Books.MINERVINI);
    assertThat(Books.fromTags(List.of("manas-arora"))).isEqualTo(Books.MANAS_ARORA);
  }

  @Test
  void precedenceIsScalperThenMinerviniThenManasRegardlessOfTagOrder() {
    // A strategy carrying several family tags resolves to the HIGHEST-precedence one, and the order
    // of the tag collection must not change the answer.
    assertThat(Books.fromTags(List.of("minervini", "scalper"))).isEqualTo(Books.SCALPER);
    assertThat(Books.fromTags(List.of("scalper", "minervini"))).isEqualTo(Books.SCALPER);
    assertThat(Books.fromTags(List.of("manas-arora", "minervini"))).isEqualTo(Books.MINERVINI);
    assertThat(Books.fromTags(Set.of("manas-arora", "minervini", "scalper"))).isEqualTo(Books.SCALPER);
  }

  @Test
  void unknownEmptyOrNullTagsFallBackToOther() {
    assertThat(Books.fromTags(List.of("momentum", "nifty"))).isEqualTo(Books.OTHER);
    assertThat(Books.fromTags(List.of())).isEqualTo(Books.OTHER);
    assertThat(Books.fromTags(null)).isEqualTo(Books.OTHER);
    // 'manual' is not a family tag a strategy carries — a tagless/hand strategy is OTHER, not MANUAL.
    assertThat(Books.fromTags(List.of("manual"))).isEqualTo(Books.OTHER);
  }
}
