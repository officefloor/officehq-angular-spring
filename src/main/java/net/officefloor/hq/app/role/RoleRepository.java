package net.officefloor.hq.app.role;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {

    /** The roles set on the sheet, by user name. */
    List<Role> findBySheetIdOrderByUserNameAsc(String sheetId);

    /** The role set for a user name on the sheet. */
    Optional<Role> findBySheetIdAndUserName(String sheetId, String userName);
}
