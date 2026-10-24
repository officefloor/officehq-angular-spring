package net.officefloor.hq.app.claim;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetTagRepository extends JpaRepository<SheetTag, SheetTag.Key> {

    /** The tags ever used on the sheet's claims, sorted. */
    List<SheetTag> findBySheetIdOrderByTagAsc(String sheetId);
}
