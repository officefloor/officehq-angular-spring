package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * A sequence of claim numbers within a period on a sheet, keyed by the numbers' prefix: the period's
 * name, or X for claims in no period. It keeps the last number given, so a number is never given again.
 */
@Entity
@Table(name = "claim_sequence")
@IdClass(ClaimSequence.Key.class)
public class ClaimSequence {

    /** The sequence's key: its sheet and prefix. */
    public static class Key implements Serializable {
        private String sheetId;
        private String prefix;

        protected Key() {
        }

        public Key(String sheetId, String prefix) {
            this.sheetId = sheetId;
            this.prefix = prefix;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(sheetId, key.sheetId) && Objects.equals(prefix, key.prefix);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sheetId, prefix);
        }
    }

    @Id
    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Id
    @Column(name = "prefix", nullable = false)
    private String prefix;

    @Column(name = "last_number", nullable = false)
    private long lastNumber;

    protected ClaimSequence() {
    }

    public ClaimSequence(String sheetId, String prefix) {
        this.sheetId = sheetId;
        this.prefix = prefix;
    }

    /** Give out the sequence's next number: &lt;prefix&gt;-&lt;n&gt;, counting from 1. */
    public String take() {
        return prefix + "-" + ++lastNumber;
    }
}
