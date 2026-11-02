package net.officefloor.hq.app.sheet;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetSourceRepository extends JpaRepository<SheetSource, String> {

    /** The sheet the sheet was cloned from; null when it is no clone. */
    default String sourceOf(String sheetId) {
        return findById(sheetId).map(SheetSource::getSourceSheet).orElse(null);
    }
}
