package net.officefloor.hq.app.claim;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimTagRepository extends JpaRepository<ClaimTag, Long> {

    List<ClaimTag> findBySheetIdOrderByTagAsc(String sheetId);

    boolean existsBySheetIdAndTag(String sheetId, String tag);
}
