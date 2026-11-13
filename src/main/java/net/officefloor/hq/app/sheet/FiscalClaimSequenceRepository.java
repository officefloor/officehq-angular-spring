package net.officefloor.hq.app.sheet;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalClaimSequenceRepository extends JpaRepository<FiscalClaimSequence, Long> {

    Optional<FiscalClaimSequence> findBySheetIdAndFiscalYear(String sheetId, int fiscalYear);

    List<FiscalClaimSequence> findBySheetIdInAndFiscalYear(Collection<String> sheetIds, int fiscalYear);
}
