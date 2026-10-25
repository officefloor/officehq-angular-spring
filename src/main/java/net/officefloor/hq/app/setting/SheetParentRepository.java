package net.officefloor.hq.app.setting;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetParentRepository extends JpaRepository<SheetParentLink, String> {

    /** The links of the sheets naming the sheet as their parent: its children. */
    List<SheetParentLink> findByParentId(String parentId);
}
