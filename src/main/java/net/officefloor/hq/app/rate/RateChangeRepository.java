package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.ArrayList;
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
        return stacksAsOf(sheetId, day).values().stream().map(l -> l.get(l.size() - 1)).toList();
    }

    /**
     * Every rate set while the sheet's today was on or before the day and not withdrawn by then, each grade and
     * from-date's in the order set (see {@link Rate#seenBy}).
     */
    default List<Rate> versionsAsOf(String sheetId, LocalDate day) {
        return stacksAsOf(sheetId, day).values().stream().flatMap(List::stream).toList();
    }

    /**
     * For each grade and from-date, the versions set while the sheet's today was on or before the day, oldest first,
     * each withdrawal by then taking off the newest; one with none left is left out.
     */
    private Map<List<Object>, List<Rate>> stacksAsOf(String sheetId, LocalDate day) {
        Map<List<Object>, List<Rate>> by = new LinkedHashMap<>();
        for (RateChange c : findBySheetIdOrderByIdAsc(sheetId)) {
            if (!c.madeBy(day)) {
                continue;
            }
            List<Object> key = Arrays.asList(c.getGrade(), c.getFrom());
            if (c.isWithdrawn()) {
                List<Rate> stack = by.get(key);
                if (stack != null) {
                    stack.remove(stack.size() - 1);
                    if (stack.isEmpty()) {
                        by.remove(key);
                    }
                }
            } else {
                by.computeIfAbsent(key, k -> new ArrayList<>()).add(c.rate());
            }
        }
        return by;
    }
}
