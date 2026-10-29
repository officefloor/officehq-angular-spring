package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateChangeRepository extends JpaRepository<RateChange, Long> {

    List<RateChange> findBySheetIdOrderByIdAsc(String sheetId);

    /**
     * The rates the sheet had at the end of the day: for each grade and from-date, the last rate set for it while the
     * sheet's today was on or before the day.
     */
    default List<Rate> ratesAsOf(String sheetId, LocalDate day) {
        Map<List<Object>, Rate> by = new LinkedHashMap<>();
        for (RateChange c : findBySheetIdOrderByIdAsc(sheetId)) {
            if (c.madeBy(day)) {
                by.put(Arrays.asList(c.getGrade(), c.getFrom()), c.rate());
            }
        }
        return List.copyOf(by.values());
    }

    /** Every rate set while the sheet's today was on or before the day, in the order set (see {@link Rate#seenBy}). */
    default List<Rate> versionsAsOf(String sheetId, LocalDate day) {
        return findBySheetIdOrderByIdAsc(sheetId).stream().filter(c -> c.madeBy(day)).map(RateChange::rate).toList();
    }
}
