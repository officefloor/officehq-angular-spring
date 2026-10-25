package net.officefloor.hq.app.setting;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DelegationRepository extends JpaRepository<Delegation, Long> {

    /** The sheet's delegations in the order they were made. */
    List<Delegation> findBySheetIdOrderByIdAsc(String sheetId);

    /** The user's delegations on the sheet, the latest made first. */
    List<Delegation> findBySheetIdAndUserNameOrderByIdDesc(String sheetId, String userName);
}
