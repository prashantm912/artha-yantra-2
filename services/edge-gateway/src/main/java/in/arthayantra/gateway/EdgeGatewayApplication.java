package in.arthayantra.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The sole ingress (A.2 / ADR D7): route table, login, sessions, headers, WS bridge. */
@SpringBootApplication
public class EdgeGatewayApplication {

  /** Boot entry point. */
  public static void main(String[] args) {
    SpringApplication.run(EdgeGatewayApplication.class, args);
  }
}
