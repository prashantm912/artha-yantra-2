package in.arthayantra.common.web.error;

import java.util.Map;

/** 409 with a {@code CONFLICT_*} family code (A.4). */
public class ConflictException extends ApiException {

  public ConflictException(String code, String message) {
    super(409, code, message);
  }

  public ConflictException(String code, String message, Map<String, Object> details) {
    super(409, code, message, details);
  }
}
