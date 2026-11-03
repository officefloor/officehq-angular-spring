package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** The last claim number a sheet gave within a fiscal year (FYyyyy); only ever moved on by
 *  {@link SheetService#nextClaimNumber}. */
@Entity
@Table(name = "fiscal_claim_number")
@IdClass(FiscalClaimNumber.Key.class)
public class FiscalClaimNumber {

    /** The key of a fiscal claim number: its sheet and fiscal year. */
    public static class Key implements Serializable {
        private String sheetId;
        private String fiscalYear;

        protected Key() {
        }

        public Key(String sheetId, String fiscalYear) {
            this.sheetId = sheetId;
            this.fiscalYear = fiscalYear;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(sheetId, k.sheetId) && Objects.equals(fiscalYear, k.fiscalYear);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sheetId, fiscalYear);
        }
    }

    @Id
    @Column(name = "sheet_id")
    private String sheetId;

    @Id
    @Column(name = "fiscal_year")
    private String fiscalYear;

    @Column(name = "last_number", nullable = false)
    private int lastNumber;

    protected FiscalClaimNumber() {
    }

    public FiscalClaimNumber(String sheetId, String fiscalYear, int lastNumber) {
        this.sheetId = sheetId;
        this.fiscalYear = fiscalYear;
        this.lastNumber = lastNumber;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getFiscalYear() {
        return fiscalYear;
    }

    public int getLastNumber() {
        return lastNumber;
    }

    public void setLastNumber(int lastNumber) {
        this.lastNumber = lastNumber;
    }
}
