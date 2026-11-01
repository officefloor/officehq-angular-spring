package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The sheet a sheet names as its parent (not dated): a grade with no rate in force on a date on the
 * child takes the parent's for that grade and date, then the parent's parent's, up to three levels up.
 * Not one of the sheet's own rows, so viewing a sheet as of a date shows the parent it names now.
 */
@Entity
@Table(name = "sheet_parent")
public class SheetParent {

    /** How many levels up a grade's rate is looked for once the sheet has none in force. */
    public static final int LEVELS = 3;

    @Id
    @Column(name = "child_sheet")
    private String childSheet;

    @Column(name = "parent_sheet", nullable = false)
    private String parentSheet;

    protected SheetParent() {}

    public SheetParent(String childSheet, String parentSheet) {
        this.childSheet = childSheet;
        this.parentSheet = parentSheet;
    }

    public String getChildSheet() { return childSheet; }

    public String getParentSheet() { return parentSheet; }

    public void setParentSheet(String parentSheet) { this.parentSheet = parentSheet; }
}
