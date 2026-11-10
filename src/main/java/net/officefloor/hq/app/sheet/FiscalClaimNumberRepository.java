package net.officefloor.hq.app.sheet;

import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FiscalClaimNumberRepository extends JpaRepository<FiscalClaimNumber, FiscalClaimNumber.Key> {

    /** The last claim number given in the fiscal year on any of the sheets, as the store holds it now; 0 when none. */
    @Query("SELECT COALESCE(MAX(f.lastNumber), 0) FROM FiscalClaimNumber f WHERE f.sheetId IN :ids AND f.fiscalYear = :fiscalYear")
    int maxLastNumber(Collection<String> ids, String fiscalYear);
}
