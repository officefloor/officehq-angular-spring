package net.officefloor.hq.app.role;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DelegationRepository extends JpaRepository<Delegation, Long> {

    List<Delegation> findBySheetIdOrderByIdAsc(String sheetId);

    List<Delegation> findBySheetIdAndUserNameOrderByIdDesc(String sheetId, String userName);
}
