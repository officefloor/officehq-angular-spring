package net.officefloor.hq.app.setting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The sheet a sheet names as its parent. Not dated, and not part of the sheet's as-of history. */
@Entity
@Table(name = "sheet_parent")
public class SheetParentLink {

    @Id
    @Column(name = "child_id")
    private String sheetId;

    @Column(name = "parent_id", nullable = false)
    private String parentId;

    protected SheetParentLink() {
    }

    public SheetParentLink(String sheetId, String parentId) {
        this.sheetId = sheetId;
        this.parentId = parentId;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }
}
