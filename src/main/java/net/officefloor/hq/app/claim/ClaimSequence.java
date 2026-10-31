package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A sequence of claim numbers within a period of a sheet, keyed by its prefix: the period's name, or X for claims
 *  in no period. It keeps the last number it gave out. */
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

    @Column(name = "last_number", nullable = false)
    private int lastNumber;

    protected ClaimSequence() {
    }

    public ClaimSequence(String sheetId, String prefix) {
        this.sheetId = sheetId;
        this.prefix = prefix;
    }

    /** Take the next number in this sequence, as <prefix>-<n>; it is never given out again. */
    public String next() {
        return prefix + "-" + (++lastNumber);
    }
}
