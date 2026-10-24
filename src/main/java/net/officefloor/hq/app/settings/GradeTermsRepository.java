package net.officefloor.hq.app.settings;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GradeTermsRepository extends JpaRepository<GradeTerms, Long> {

    List<GradeTerms> findBySheetIdOrderByGrade(String sheetId);

    Optional<GradeTerms> findBySheetIdAndGrade(String sheetId, String grade);
}
