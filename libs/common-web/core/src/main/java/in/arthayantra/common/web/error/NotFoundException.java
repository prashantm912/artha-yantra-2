package in.arthayantra.common.web.error;

import java.util.Map;

/** 404 with a {@code NOT_FOUND_*} family code (A.4). */
public class NotFoundException extends ApiException {

  /** Not-found without details. */
  public NotFoundException(String code, String message) {
    super(404, code, message);
  }

  /** Not-found with details. */
  public NotFoundException(String code, String message, Map<String, Object> details) {
    super(404, code, message, details);
  }
}
