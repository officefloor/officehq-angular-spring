package net.officefloor.hq.app.settings;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DelegationRepository extends JpaRepository<Delegation, Long> {

    List<Delegation> findBySheetIdOrderByIdAsc(String sheetId);

    List<Delegation> findBySheetIdAndUserOrderByIdAsc(String sheetId, String user);
}
