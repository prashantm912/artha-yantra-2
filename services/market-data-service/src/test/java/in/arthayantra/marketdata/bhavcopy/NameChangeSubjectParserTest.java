package in.arthayantra.marketdata.bhavcopy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Recognition + name extraction for NSE/BSE "Change in Name" corporate-action subjects. */
class NameChangeSubjectParserTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Change In Name From Gujarat Gas Limited To Gujarat Energy Limited",
        "CHANGE IN NAME",
        "Change of Name",
        "Change in the Name",
        "Change in Company Name",
        "Name Change"
      })
  void recognisesNameChangeSubjects(String subject) {
    assertThat(NameChangeSubjectParser.isNameChange(subject)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Bonus 1:3",
        "Face Value Split From Rs 10/- To Re 1/-",
        "FINAL DIVIDEND RS 2.50 PER SHARE",
        "Annual General Meeting",
        "Buy Back of Shares",
        // "Change in Face Value" is a split, NOT a name change — the word "name" is absent, and
        // that is exactly the boundary a looser "change in" pattern would blur.
        "Change in Face Value From Rs 10/- To Rs 2/-"
      })
  void rejectsEverythingElse(String subject) {
    assertThat(NameChangeSubjectParser.isNameChange(subject)).isFalse();
  }

  @Test
  void nullAndBlankAreNotNameChanges() {
    assertThat(NameChangeSubjectParser.isNameChange(null)).isFalse();
    assertThat(NameChangeSubjectParser.isNameChange("   ")).isFalse();
  }

  @Test
  void extractsBothCompanyNames() {
    assertThat(
            NameChangeSubjectParser.parseNames(
                "Change In Name From Lypsa Gems & Jewellery Limited To Aurus Gem Corp Limited"))
        .hasValueSatisfying(
            n -> {
              assertThat(n.fromName()).isEqualTo("Lypsa Gems & Jewellery Limited");
              assertThat(n.toName()).isEqualTo("Aurus Gem Corp Limited");
            });
  }

  @Test
  void aBareAnnouncementYieldsNoNamesButIsStillANameChange() {
    assertThat(NameChangeSubjectParser.isNameChange("Change In Name")).isTrue();
    assertThat(NameChangeSubjectParser.parseNames("Change In Name")).isEmpty();
  }

  @Test
  void neverExtractsNamesFromASubjectItDoesNotRecognise() {
    // The from/to shape ALSO matches a face-value split. Gating extraction behind isNameChange is
    // what stops "Rs 10/-" -> "Re 1/-" being recorded as a pair of company names.
    assertThat(NameChangeSubjectParser.parseNames("Face Value Split From Rs 10/- To Re 1/-"))
        .isEmpty();
  }
}
