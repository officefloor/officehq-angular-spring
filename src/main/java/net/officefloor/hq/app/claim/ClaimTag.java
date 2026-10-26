package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A tag used on a claim of a sheet, kept once it has been used: the sheet shows a due-total for each. */
@Entity
@Table(name = "claim_tag")
public class ClaimTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "tag", nullable = false)
    private String tag;

    protected ClaimTag() {
    }

    public ClaimTag(String sheetId, String tag) {
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
