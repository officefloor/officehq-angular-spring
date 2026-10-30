package net.officefloor.hq.app.rate;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RateVersionRepository extends JpaRepository<RateVersion, Long> {

    /** The older versions of the sheet's rates, by rate and then version. */
    @Query("SELECT v FROM RateVersion v WHERE v.rateId IN (SELECT r.id FROM Rate r WHERE r.sheetId = :sheetId)"
            + " ORDER BY v.rateId, v.version")
    List<RateVersion> findBySheet(String sheetId);

    /** The rate's older version with the number, if it has one. */
    Optional<RateVersion> findByRateIdAndVersion(Long rateId, int version);
}
