package in.arthayantra.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * CD-13: emits an Argon2id PHC string for {@code ARTHA_OWNER_PASSWORD_HASH} using exactly the
 * A.2.3 parameters the gateway verifies with — saltLength=16, hashLength=32, p=1, m=19456 KiB,
 * t=2. Pass the password as the exec argument, or pipe it on stdin.
 */
public final class HashPassword {

  private HashPassword() {}

  /** Prints the PHC string for the given password. */
  public static void main(String[] args) throws IOException {
    String password;
    if (args.length > 0 && !args[0].isBlank()) {
      password = args[0];
    } else {
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
        password = reader.readLine();
      }
    }
    if (password == null || password.isBlank()) {
      System.err.println("usage: mvnw -pl tools/hash-password -am exec:java -Dexec.args=<password>");
      System.exit(2);
      return;
    }
    Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    System.out.println(encoder.encode(password));
  }
}
