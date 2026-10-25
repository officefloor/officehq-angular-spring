package net.officefloor.hq.app.rate;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateVersionRepository extends JpaRepository<RateVersion, Long> {

    /** The rate's older versions, oldest first. */
    List<RateVersion> findByRateIdOrderByVersionAsc(Long rateId);

    Optional<RateVersion> findByRateIdAndVersion(Long rateId, int version);
}
