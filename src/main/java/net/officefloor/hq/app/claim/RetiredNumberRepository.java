package net.officefloor.hq.app.claim;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RetiredNumberRepository extends JpaRepository<RetiredNumber, Long> {

    /** The numbers re-dated claims left behind on the sheet. */
    @Query("select r.number from RetiredNumber r where r.sheetId = :sheetId")
    List<String> findNumbers(String sheetId);
}
