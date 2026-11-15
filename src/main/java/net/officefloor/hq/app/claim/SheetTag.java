package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A tag used on the sheet: kept once any claim has carried it, even after that claim drops it. */
@Entity
@Table(name = "sheet_tag")
public class SheetTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "tag", nullable = false)
    private String tag;

    protected SheetTag() {
    }

    public SheetTag(String sheetId, String tag) {
        this.sheetId = sheetId;
        this.tag = tag;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getTag() {
        return tag;
    }
}
