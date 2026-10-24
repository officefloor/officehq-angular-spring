package net.officefloor.hq.app.locale;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserLocaleRepository extends JpaRepository<UserLocale, Long> {

    Optional<UserLocale> findBySheetIdAndUser(String sheetId, String user);
}
