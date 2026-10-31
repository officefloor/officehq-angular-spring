package net.officefloor.hq.app.claim;

import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimFiscalSequenceRepository extends JpaRepository<ClaimFiscalSequence, Long> {

    Optional<ClaimFiscalSequence> findBySheetIdAndFiscalYear(String sheetId, int fiscalYear);

    /** The last number given in the fiscal year by any of these sheets; 0 when none has given one. */
    @Query("select coalesce(max(s.lastNumber), 0) from ClaimFiscalSequence s"
            + " where s.sheetId in :ids and s.fiscalYear = :year")
    int lastNumberOf(@Param("ids") Collection<String> ids, @Param("year") int fiscalYear);
}
