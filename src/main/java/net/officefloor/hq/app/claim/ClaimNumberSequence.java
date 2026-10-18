package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One of a sheet's period numbering sequences: the last number it gave out to a claim of the period
 * named by its prefix (X: no period). Its numbers are never reused.
 */
@Entity
@Table(name = "claim_number_sequence")
public class ClaimNumberSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "prefix", nullable = false)
    private String prefix;

    @Column(name = "last_number", nullable = false)
    private int lastNumber;

    protected ClaimNumberSequence() {}

    public ClaimNumberSequence(String sheetId, String prefix) {
        this.sheetId = sheetId;
        this.prefix = prefix;
    }

    public String getSheetId() { return sheetId; }

    public String getPrefix() { return prefix; }

    /** Give out the sequence's next number. */
    public int take() { return ++lastNumber; }
}
