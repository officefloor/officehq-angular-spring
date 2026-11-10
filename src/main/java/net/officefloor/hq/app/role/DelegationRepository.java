package net.officefloor.hq.app.role;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DelegationRepository extends JpaRepository<Delegation, Long> {

    /** A sheet's delegations in the order they were made. */
    List<Delegation> findBySheetIdOrderByIdAsc(String sheetId);

    /** A user's delegations on a sheet, newest first. */
    List<Delegation> findBySheetIdAndUserNameOrderByIdDesc(String sheetId, String userName);
}
