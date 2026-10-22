package net.officefloor.hq.app.role;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DelegationRepository extends JpaRepository<Delegation, Long> {

    /** The delegations made on the sheet, in the order they were made. */
    List<Delegation> findBySheetIdOrderByIdAsc(String sheetId);

    /** The delegations made to a user name on the sheet, latest first. */
    List<Delegation> findBySheetIdAndUserNameOrderByIdDesc(String sheetId, String userName);
}
