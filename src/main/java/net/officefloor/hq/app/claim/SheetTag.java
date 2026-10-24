package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** A tag used on a claim of a sheet: kept once it has been used, whatever later happens to the claim. */
@Entity
@Table(name = "sheet_tag")
@IdClass(SheetTag.Key.class)
public class SheetTag {

    /** A tag's key: its sheet and the tag. */
    public static class Key implements Serializable {
        private String sheetId;
        private String tag;

        protected Key() {
        }

        public Key(String sheetId, String tag) {
            this.sheetId = sheetId;
            this.tag = tag;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(sheetId, key.sheetId) && Objects.equals(tag, key.tag);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sheetId, tag);
        }
    }

    @Id
    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Id
    @Column(name = "tag", nullable = false)
    private String tag;

    protected SheetTag() {
    }

    public SheetTag(String sheetId, String tag) {
        this.sheetId = sheetId;
        this.tag = tag;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getTag() {
        return tag;
    }
}
