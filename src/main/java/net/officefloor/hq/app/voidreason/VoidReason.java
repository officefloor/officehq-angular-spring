package net.officefloor.hq.app.voidreason;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A reason on a sheet's list that a claim may be voided for. */
@Entity
@Table(name = "void_reason")
public class VoidReason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "reason", nullable = false)
    private String reason;

    /** The sheet's today when the reason was added. */
    @Column(name = "added_on", nullable = false)
    private LocalDate addedOn;

    protected VoidReason() {
    }

    public VoidReason(String sheetId, String reason, LocalDate addedOn) {
        this.sheetId = sheetId;
        this.reason = reason;
        this.addedOn = addedOn;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getReason() {
        return reason;
    }

    public LocalDate getAddedOn() {
        return addedOn;
    }
}
