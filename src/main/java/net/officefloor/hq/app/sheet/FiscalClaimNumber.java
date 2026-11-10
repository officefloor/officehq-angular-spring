package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** The last claim number a sheet gave in a fiscal year (FY&lt;yyyy&gt;); the next one opened takes the one after. */
@Entity
@Table(name = "fiscal_claim_number")
@IdClass(FiscalClaimNumber.Key.class)
public class FiscalClaimNumber {

    @Id
    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Id
    @Column(name = "fiscal_year", nullable = false)
    private String fiscalYear;

    @Column(name = "last_number", nullable = false)
    private int lastNumber;

    protected FiscalClaimNumber() {
    }

    public FiscalClaimNumber(String sheetId, String fiscalYear) {
        this.sheetId = sheetId;
        this.fiscalYear = fiscalYear;
    }

    public int getLastNumber() {
        return lastNumber;
    }

    /** Give out the next number: the one after {@code lastInTree}, the last given in the fiscal year anywhere in the tree. */
    public int next(int lastInTree) {
        lastNumber = Math.max(lastNumber, lastInTree) + 1;
        return lastNumber;
    }

    /** The composite key: a sheet and a fiscal year. */
    public static class Key implements Serializable {
        private String sheetId;
        private String fiscalYear;

        public Key() {
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
}
