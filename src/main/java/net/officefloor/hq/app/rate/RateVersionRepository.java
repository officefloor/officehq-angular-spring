package net.officefloor.hq.app.rate;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateVersionRepository extends JpaRepository<RateVersion, Long> {

    /** The rate's kept version with this number. */
    Optional<RateVersion> findByRateIdAndVersion(Long rateId, int version);
}
