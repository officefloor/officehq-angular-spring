# OfficeHQ

A full-stack office-management web application.

- **Front end:** Angular single-page app (standalone components, lazy routes), built into
  `src/main/resources/static`.
- **Back end:** Spring Boot with Spring MVC `@RestController` endpoints, on an in-memory H2 database
  with Flyway migrations run on boot.

## Build and run

```
bin/build              # build the SPA and package one runnable jar
PORT=3000 bin/start    # start the app (serves the SPA + REST on $PORT)
bin/stop               # stop it
```

Readiness: `GET /actuator/health`.

## Tests

End-to-end tests use Playwright (in `e2e/`) and bind to `data-testid`:

```
bin/e2e                # build, start, run the specs in e2e/specs, then stop
```
