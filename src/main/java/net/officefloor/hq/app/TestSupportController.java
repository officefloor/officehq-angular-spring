package net.officefloor.hq.app;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-spec data setup for the end-to-end tests. Profile-guarded so it exists ONLY under the test
 * launch (bin/start sets spring.profiles.active=e2e) — never in a real deploy. Tests call these to
 * ARRANGE data; they ASSERT only through the UI.
 */
@Profile("e2e")
@RestController
@RequestMapping("/__test__")
public class TestSupportController {

    private final Audit audit;

    public TestSupportController(Audit audit) {
        this.audit = audit;
    }

    /** Truncate all domain tables and clear the audit file so each spec starts clean. */
    @PostMapping("/reset")
    public void reset() {
        audit.clear();
        // TODO: TRUNCATE the domain tables that currently exist (inject a JdbcTemplate/repo).
    }

    /** Insert the fixture a spec needs; the payload shape evolves with the schema. */
    @PostMapping("/seed")
    public void seed(@RequestBody Map<String, Object> fixture) {
        // TODO: insert rows for the fixture.
    }
}
