package net.officefloor.hq.app.setting;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GradeTermsRepository extends JpaRepository<GradeTerms, Long> {

    /** A sheet's grade terms in grade order. */
    List<GradeTerms> findBySheetIdOrderByGrade(String sheetId);

    Optional<GradeTerms> findBySheetIdAndGrade(String sheetId, String grade);
}
