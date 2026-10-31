package net.officefloor.hq.app.rate;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddedGradeRepository extends JpaRepository<AddedGrade, Long> {

    /** The grades added to the sheet, in the order added. */
    List<AddedGrade> findBySheetIdOrderById(String sheetId);
}
