package in.arthayantra.common.web.servlet;

import in.arthayantra.common.web.error.ConflictException;
import in.arthayantra.common.web.error.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Minimal app driving the adapter through real MVC machinery in tests. */
@SpringBootApplication
public class ServletAdapterTestApp {

  record CreateThing(@NotBlank String name) {}

  @RestController
  static class ThrowingController {

    @GetMapping("/things/missing")
    String missing() {
      throw new NotFoundException("NOT_FOUND_THING", "no such thing");
    }

    @GetMapping("/things/conflicted")
    String conflicted() {
      throw new ConflictException("CONFLICT_THING_NAME", "name already used");
    }

    @PostMapping("/things")
    String create(@Valid @RequestBody CreateThing body) {
      return "created";
    }

    @GetMapping("/things/boom")
    String boom() {
      throw new IllegalStateException("kaboom with secret detail");
    }

    @GetMapping("/things/mdc")
    String mdc() {
      return MDC.get("requestId") + "|" + MDC.get("user");
    }
  }

  /**
   * The idiomatic Spring answer to "reject a bad query param": class-level {@code @Validated} plus a
   * constraint on the parameter. It must live in a REAL application context, because that is the
   * only place the mechanism exists — {@code @Validated} makes Spring Boot wrap the bean in a CGLIB
   * proxy whose {@code MethodValidationInterceptor} throws before the method body runs. A
   * standalone-MockMvc controller is a plain instance with no proxy, so it would never raise
   * anything and the test would pass vacuously.
   */
  @RestController
  @Validated
  static class ConstrainedController {

    @GetMapping("/things/window")
    String window(@RequestParam @Min(1) int lookbackDays) {
      return "window:" + lookbackDays;
    }
  }
}
