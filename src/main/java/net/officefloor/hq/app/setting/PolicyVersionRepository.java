package net.officefloor.hq.app.setting;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyVersionRepository extends JpaRepository<PolicyVersion, Long> {

    List<PolicyVersion> findBySheetIdOrderByNumberAsc(String sheetId);

    long countBySheetId(String sheetId);
}
