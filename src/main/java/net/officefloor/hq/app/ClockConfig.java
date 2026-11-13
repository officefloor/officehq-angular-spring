package net.officefloor.hq.app;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The real clock. Only a new sheet's starting "today" reads it: every other date-dependent
 * decision and default uses the sheet's today (Sheet#getToday), never this clock.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
