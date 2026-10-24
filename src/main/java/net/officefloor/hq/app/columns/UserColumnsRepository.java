package net.officefloor.hq.app.columns;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserColumnsRepository extends JpaRepository<UserColumns, Long> {

    Optional<UserColumns> findBySheetIdAndUser(String sheetId, String user);
}
