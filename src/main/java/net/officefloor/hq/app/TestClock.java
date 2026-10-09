package net.officefloor.hq.app;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Clock for the end-to-end tests: the system clock, unless a spec has pinned "today" via
 * /__test__/seed. Profile-guarded like {@link TestSupportController}, and takes precedence over the
 * application's system clock only under that profile.
 */
@Profile("e2e")
@Primary
@Component
public class TestClock extends Clock {

    private final ZoneId zone = ZoneId.systemDefault();
    private volatile LocalDate today;

    /** Pin "today" to the given date (at the start of the day). */
    public void setToday(LocalDate today) {
        this.today = today;
    }

    /** Return to the system clock. */
    public void reset() {
        this.today = null;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.system(zone);
    }

    @Override
    public Instant instant() {
        LocalDate pinned = today;
        return pinned == null ? Instant.now() : pinned.atStartOfDay(zone).toInstant();
    }
}
