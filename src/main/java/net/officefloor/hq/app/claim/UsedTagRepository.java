package net.officefloor.hq.app.claim;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsedTagRepository extends JpaRepository<UsedTag, Long> {

    /** Every tag ever used on a sheet, sorted. */
    List<UsedTag> findBySheetIdOrderByTagAsc(String sheetId);

    boolean existsBySheetIdAndTag(String sheetId, String tag);
}
