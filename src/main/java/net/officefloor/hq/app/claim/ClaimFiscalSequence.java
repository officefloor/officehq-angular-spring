package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The last sheet-wide number a sheet gave within a fiscal year (keyed by the calendar year it begins in). The
 *  sequence is shared across the sheet's tree, so the next number follows the last one given anywhere in it. */
@Entity
@Table(name = "claim_fiscal_sequence")
public class ClaimFiscalSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "fiscal_year", nullable = false)
    private int fiscalYear;

    @Column(name = "last_number", nullable = false)
    private int lastNumber;

    protected ClaimFiscalSequence() {
    }

    public ClaimFiscalSequence(String sheetId, int fiscalYear) {
        this.sheetId = sheetId;
        this.fiscalYear = fiscalYear;
    }

    /** Take the next number in this fiscal year after the last one given anywhere in the tree (this sheet's own
     *  last included), as FY<year>-<n>; it is never given out again. */
    public String nextAfter(int lastInTree) {
        lastNumber = Math.max(lastNumber, lastInTree) + 1;
        return "FY" + fiscalYear + "-" + lastNumber;
    }
}
