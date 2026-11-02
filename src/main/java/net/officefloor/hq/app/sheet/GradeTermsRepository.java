package net.officefloor.hq.app.sheet;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GradeTermsRepository extends JpaRepository<GradeTerms, GradeTerms.Key> {

    List<GradeTerms> findBySheetIdOrderByGrade(String sheetId);
}
