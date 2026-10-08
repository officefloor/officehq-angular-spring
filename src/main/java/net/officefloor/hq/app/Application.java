package net.officefloor.hq.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot host: serves the SPA from static/, with H2 + Flyway (schema migrated on boot) and
 * Actuator. Domain REST endpoints, @Service/@Repository beans and migrations are added under
 * src/main/java and src/main/resources.
 */
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
