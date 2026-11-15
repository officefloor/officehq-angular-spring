package net.officefloor.hq.app.settings;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetDelegationRepository extends JpaRepository<SheetDelegation, Long> {

    /** The sheet's delegations, in the order they were made. */
    List<SheetDelegation> findBySheetIdOrderByIdAsc(String sheetId);

    /** The delegations to a user name on the sheet, the latest first. */
    List<SheetDelegation> findBySheetIdAndUserOrderByIdDesc(String sheetId, String user);
}
