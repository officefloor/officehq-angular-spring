package net.officefloor.hq.app.rate;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateRepository extends JpaRepository<Rate, Rate.Key> {

    /** The sheet's rates by grade. */
    List<Rate> findBySheetIdOrderByGradeAsc(String sheetId);
}
