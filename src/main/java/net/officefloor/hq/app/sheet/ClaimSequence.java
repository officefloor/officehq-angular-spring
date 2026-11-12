package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A numbering sequence of a sheet other than its sheet-wide one: the claims of one period (its name the
 *  prefix), or of no period (X), numbered <prefix>-1, <prefix>-2, ..., never given twice. */
@Entity
@Table(name = "claim_sequence")
public class ClaimSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "prefix", nullable = false)
    private String prefix;

    /** The last number given in the sequence; 0 before the first. */
    @Column(name = "last_number", nullable = false)
    private int lastNumber;

    protected ClaimSequence() {
    }

    public ClaimSequence(String sheetId, String prefix) {
        this.sheetId = sheetId;
        this.prefix = prefix;
    }

    public String getPrefix() {
        return prefix;
    }

    /** Give out the sequence's next claim number. */
    public String nextClaimNumber() {
        return prefix + "-" + ++lastNumber;
    }
}
