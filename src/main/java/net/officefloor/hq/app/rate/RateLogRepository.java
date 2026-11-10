package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateLogRepository extends JpaRepository<RateLog, Long> {

    /** A sheet's rates as set, oldest first. */
    List<RateLog> findBySheetIdOrderByIdAsc(String sheetId);

    /**
     * The sheet's rates as they stood at the end of a day: each set while the sheet's today was on
     * or before it, a later one for the same grade and from-date replacing an earlier as its newest
     * version. Not to be saved.
     */
    default List<Rate> asOf(String sheetId, LocalDate day) {
        Map<List<Object>, Rate> by = new LinkedHashMap<>();
        for (RateLog set : findBySheetIdOrderByIdAsc(sheetId)) {
            if (set.madeBy(day)) {
                Rate rate = set.toRate();
                Rate older = by.get(Arrays.asList(set.getGrade(), set.getFromDate()));
                rate.setVersion(older == null ? 1 : older.getVersion() + 1);
                by.put(Arrays.asList(set.getGrade(), set.getFromDate()), rate);
            }
        }
        return new ArrayList<>(by.values());
    }
}
