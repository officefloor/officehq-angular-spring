package net.officefloor.hq.app.setting;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SheetParentRepository extends JpaRepository<SheetParentLink, String> {

    /** The links of the sheets naming the sheet as their parent: its children. */
    List<SheetParentLink> findByParentId(String parentId);

    /** The sheet the sheet was cloned from; empty when it was not cloned. */
    @Query(value = "SELECT source_id FROM sheet_source WHERE child_id = ?1", nativeQuery = true)
    Optional<String> findSourceOf(String sheetId);
}
