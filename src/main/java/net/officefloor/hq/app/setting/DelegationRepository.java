package net.officefloor.hq.app.setting;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DelegationRepository extends JpaRepository<Delegation, Long> {

    List<Delegation> findBySheetIdOrderByIdAsc(String sheetId);

    List<Delegation> findBySheetIdAndUserOrderByIdDesc(String sheetId, String user);
}
