package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A tag used on a sheet: kept, once, from the first time a claim on the sheet carries it. */
@Entity
@Table(name = "claim_tag_used")
public class UsedTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "tag", nullable = false)
    private String tag;

    protected UsedTag() {
    }

    public UsedTag(String sheetId, String tag) {
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
