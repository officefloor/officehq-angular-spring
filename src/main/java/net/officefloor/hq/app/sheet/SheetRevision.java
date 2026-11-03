package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** A revision of a sheet's own figure settings in its history: its policy date and its pot (each null:
 *  blank) after a change, recorded on the sheet's today then. */
@Entity
@Table(name = "sheet_revision")
public class SheetRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    /** The sheet's today when the revision was recorded. */
    @Column(name = "recorded_on", nullable = false)
    private LocalDate recordedOn;

    @Column(name = "policy_date")
    private LocalDate policyDate;

    @Column(name = "pot")
    private BigDecimal pot;

    protected SheetRevision() {
    }

    public SheetRevision(String sheetId, LocalDate recordedOn, LocalDate policyDate, BigDecimal pot) {
        this.sheetId = sheetId;
        this.recordedOn = recordedOn;
        this.policyDate = policyDate;
        this.pot = pot;
    }

    public LocalDate getRecordedOn() {
        return recordedOn;
    }

    public LocalDate getPolicyDate() {
        return policyDate;
    }

    public BigDecimal getPot() {
        return pot;
    }

    /** Whether the revision holds the sheet's figure settings as they are. */
    public boolean matches(Sheet sheet) {
        return Objects.equals(policyDate, sheet.getPolicyDate())
                && (pot == null ? sheet.getPot() == null : sheet.getPot() != null && pot.compareTo(sheet.getPot()) == 0);
    }
}
