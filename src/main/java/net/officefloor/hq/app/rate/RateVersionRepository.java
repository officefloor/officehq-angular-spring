package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateVersionRepository extends JpaRepository<RateVersion, Long> {

    /** A grade's older versions on a sheet, newest first. */
    List<RateVersion> findBySheetIdAndGradeOrderByVersionDesc(String sheetId, String grade);

    /** The newest older version of the grade's rate from a from-date (or its undated rate when null). */
    default Optional<RateVersion> findNewest(String sheetId, String grade, LocalDate fromDate) {
        return findBySheetIdAndGradeOrderByVersionDesc(sheetId, grade).stream()
                .filter(v -> Objects.equals(v.getFromDate(), fromDate)).findFirst();
    }
}
