package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The sheet-wide numbering of a sheet in one fiscal year (named by the calendar year its first day falls
 *  in), numbered FY<year>-1, FY<year>-2, ..., shared across the sheet's tree: the next number is one past
 *  the last any sheet of the tree gave in that fiscal year. */
@Entity
@Table(name = "fiscal_claim_sequence")
public class FiscalClaimSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "fiscal_year", nullable = false)
    private int fiscalYear;

    /** The last number this sheet gave in the fiscal year; 0 before the first. */
    @Column(name = "last_number", nullable = false)
    private int lastNumber;

    protected FiscalClaimSequence() {
    }

    public FiscalClaimSequence(String sheetId, int fiscalYear) {
        this.sheetId = sheetId;
        this.fiscalYear = fiscalYear;
    }

    public int getLastNumber() {
        return lastNumber;
    }

    /** Give out the next number after the given last of the tree. */
    public String nextClaimNumber(int lastOfTree) {
        lastNumber = Math.max(lastNumber, lastOfTree) + 1;
        return "FY" + String.format("%04d", fiscalYear) + "-" + lastNumber;
    }
}
