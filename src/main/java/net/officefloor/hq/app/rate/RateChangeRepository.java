package net.officefloor.hq.app.rate;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateChangeRepository extends JpaRepository<RateChange, Long> {

    /** The sheet's rate changes in the order they were made. */
    List<RateChange> findBySheetIdOrderByIdAsc(String sheetId);
}
