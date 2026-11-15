package net.officefloor.hq.app.sheet;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetFiscalNumberRepository extends JpaRepository<SheetFiscalNumber, Long> {

    /** The last number the sheet gave in the fiscal year, if it gave any. */
    Optional<SheetFiscalNumber> findBySheetIdAndFiscalYear(String sheetId, int fiscalYear);
}
