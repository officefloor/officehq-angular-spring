package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RateRepository extends JpaRepository<Rate, Long> {

    /** The sheet's rates by grade, each grade's from the earliest from-date (none first). */
    List<Rate> findBySheetIdOrderByGradeAscFromDateAscIdAsc(String sheetId);

    /** A grade's rates on the sheet. */
    List<Rate> findBySheetIdAndGrade(String sheetId, String grade);

    /** The grade's rate with this from-date; a null from-date finds the one with none. */
    Optional<Rate> findBySheetIdAndGradeAndFromDate(String sheetId, String grade, LocalDate fromDate);

    /**
     * The rate a claim on a date uses for a grade: of the grade's rates with no from-date or one on
     * or before the date, the one with the latest; empty when the date is before every rate, or
     * after that one's to-date.
     */
    default Optional<Rate> findInForce(String sheetId, String grade, LocalDate date) {
        return inForce(findBySheetIdAndGrade(sheetId, grade), grade, date);
    }

    /** The rate in force for a grade on a date among these rates (see {@link #findInForce}). */
    static Optional<Rate> inForce(Collection<Rate> rates, String grade, LocalDate date) {
        return inForce(rates.stream(), grade, date);
    }

    /**
     * The rate in force for a grade on a date among these rates as a claim added at a place in the order
     * claims are added sees them (see {@link Rate#seenBy}): a forward-only rate saved after the claim was
     * added is passed over.
     */
    static Optional<Rate> inForceFor(Collection<Rate> rates, String grade, LocalDate date, Long added) {
        return inForce(rates.stream().filter(rate -> rate.getGrade().equals(grade))
                .flatMap(rate -> rate.seenBy(added).stream()), grade, date);
    }

    /** Where the next claim added to any sheet takes its place in the order claims are added. */
    @Query(value = "select next value for claim_added_seq", nativeQuery = true)
    long nextAddedPlace();

    private static Optional<Rate> inForce(Stream<Rate> rates, String grade, LocalDate date) {
        return rates
                .filter(rate -> rate.getGrade().equals(grade))
                .filter(rate -> rate.appliesOn(date))
                .max(Comparator.comparing(Rate::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(rate -> !rate.endedBy(date));
    }
}
