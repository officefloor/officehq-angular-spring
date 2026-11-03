package net.officefloor.hq.app.role;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {

    /** A sheet's assigned roles by user name. */
    List<Role> findBySheetIdOrderByUserNameAsc(String sheetId);

    Optional<Role> findBySheetIdAndUserName(String sheetId, String userName);
}
