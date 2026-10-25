package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.person.Grade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RateRepository extends JpaRepository<Rate, Long> {

    /** The sheet's rates in grade order, each grade's from the beginning of time first, then by from-date. */
    @Query("select r from Rate r where r.sheetId = :sheetId order by r.grade asc, r.from asc nulls first")
    List<Rate> findBySheetIdInOrder(@Param("sheetId") String sheetId);

    /** The grade's rates, with the one with no from-date first, then by from-date. */
    @Query("select r from Rate r where r.sheetId = :sheetId and r.grade = :grade order by r.from asc nulls first")
    List<Rate> findForGrade(@Param("sheetId") String sheetId, @Param("grade") Grade grade);

    /** The grade's rate with no from-date. */
    @Query("select r from Rate r where r.sheetId = :sheetId and r.grade = :grade and r.from is null")
    Optional<Rate> findFromStart(@Param("sheetId") String sheetId, @Param("grade") Grade grade);

    /** The grade's rate with this from-date. */
    @Query("select r from Rate r where r.sheetId = :sheetId and r.grade = :grade and r.from = :from")
    Optional<Rate> findFrom(@Param("sheetId") String sheetId, @Param("grade") Grade grade,
            @Param("from") LocalDate from);
}
