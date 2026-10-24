package net.officefloor.hq.app.settings;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetDelegationRepository extends JpaRepository<SheetDelegation, Long> {

    List<SheetDelegation> findBySheetIdOrderByIdAsc(String sheetId);

    List<SheetDelegation> findBySheetIdAndUserOrderByIdAsc(String sheetId, String user);
}
