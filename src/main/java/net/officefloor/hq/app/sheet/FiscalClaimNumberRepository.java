package net.officefloor.hq.app.sheet;

import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FiscalClaimNumberRepository extends JpaRepository<FiscalClaimNumber, FiscalClaimNumber.Key> {

    /** The last claim number given within the fiscal year on any of the sheets; 0 when none. */
    @Query("select coalesce(max(f.lastNumber), 0) from FiscalClaimNumber f"
            + " where f.sheetId in :ids and f.fiscalYear = :fiscalYear")
    int lastNumber(Collection<String> ids, String fiscalYear);
}
