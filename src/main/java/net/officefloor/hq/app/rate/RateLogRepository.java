package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateLogRepository extends JpaRepository<RateLog, Long> {

    /** A sheet's rates as set, oldest first. */
    List<RateLog> findBySheetIdOrderByIdAsc(String sheetId);

    /**
     * The sheet's rates as they stood at the end of a day: each set while the sheet's today was on
     * or before it, a later one for the same grade and from-date replacing an earlier as its newest
     * version, and a withdrawal putting back the version before the newest (none: the rate is gone).
     * Not to be saved.
     */
    default List<Rate> asOf(String sheetId, LocalDate day) {
        Map<List<Object>, Deque<Rate>> by = new LinkedHashMap<>();
        for (RateLog set : findBySheetIdOrderByIdAsc(sheetId)) {
            if (set.madeBy(day)) {
                Deque<Rate> kept = by.computeIfAbsent(Arrays.asList(set.getGrade(), set.getFromDate()),
                        k -> new ArrayDeque<>());
                if (set.isWithdrawn()) {
                    kept.pollLast();
                } else {
                    Rate rate = set.toRate();
                    rate.setVersion(kept.size() + 1);
                    kept.addLast(rate);
                }
            }
        }
        return by.values().stream().filter(kept -> !kept.isEmpty()).map(Deque::peekLast)
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
