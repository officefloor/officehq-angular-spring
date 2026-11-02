package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A revision of a sheet's own figure settings in its history: its policy date (null: blank) after a
 *  change, recorded on the sheet's today then. */
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

    protected SheetRevision() {
    }

    public SheetRevision(String sheetId, LocalDate recordedOn, LocalDate policyDate) {
        this.sheetId = sheetId;
        this.recordedOn = recordedOn;
        this.policyDate = policyDate;
    }

    public LocalDate getRecordedOn() {
        return recordedOn;
    }

    public LocalDate getPolicyDate() {
        return policyDate;
    }
}
