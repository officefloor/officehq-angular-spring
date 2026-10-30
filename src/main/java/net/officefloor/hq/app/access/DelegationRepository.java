package net.officefloor.hq.app.access;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DelegationRepository extends JpaRepository<Delegation, Long> {

    List<Delegation> findBySheetIdOrderById(String sheetId);

    List<Delegation> findBySheetIdAndUserNameOrderByIdDesc(String sheetId, String userName);
}
