package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The sheet a sheet was cloned from (not dated): a grade with no rate in force on a date on the clone
 * nor on its parents takes the source's for that grade and date, then the source's parents', then its
 * own source's, and so on.
 */
@Entity
@Table(name = "sheet_source")
public class SheetSource {

    @Id
    @Column(name = "child_sheet")
    private String childSheet;

    @Column(name = "source_sheet", nullable = false)
    private String sourceSheet;

    protected SheetSource() {}

    public SheetSource(String childSheet, String sourceSheet) {
        this.childSheet = childSheet;
        this.sourceSheet = sourceSheet;
    }

    public String getChildSheet() { return childSheet; }

    public String getSourceSheet() { return sourceSheet; }
}
