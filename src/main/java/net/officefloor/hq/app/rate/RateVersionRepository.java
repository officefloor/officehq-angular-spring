package net.officefloor.hq.app.rate;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateVersionRepository extends JpaRepository<RateVersion, Long> {

    /** The older versions of a rate, the newest first. */
    List<RateVersion> findByRateIdOrderByVersionDesc(Long rateId);
}
