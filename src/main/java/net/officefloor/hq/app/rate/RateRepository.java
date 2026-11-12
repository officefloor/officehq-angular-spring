package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateRepository extends JpaRepository<Rate, Long> {

    /** The sheet's rates, by grade. */
    List<Rate> findBySheetIdOrderByGradeAsc(String sheetId);

    List<Rate> findBySheetIdAndGrade(String sheetId, String grade);

    /** The grade's rate with this from-date; none given finds the one with no from-date. */
    default Optional<Rate> findBySheetIdAndGradeAndFrom(String sheetId, String grade, LocalDate from) {
        return findBySheetIdAndGrade(sheetId, grade).stream()
                .filter(r -> from == null ? r.getFromDate() == null : from.equals(r.getFromDate()))
                .findFirst();
    }

    /** The sheet's rates, by grade, followed by each ancestor's, nearest first: a grade with no rate in
     *  force on a date on a sheet takes the next sheet's (see {@code Rollup.inForce}). */
    default List<Rate> findWithAncestors(String sheetId, List<String> ancestors) {
        List<Rate> chain = new ArrayList<>(findBySheetIdOrderByGradeAsc(sheetId));
        ancestors.forEach(a -> chain.addAll(findBySheetIdOrderByGradeAsc(a)));
        return chain;
    }
}
