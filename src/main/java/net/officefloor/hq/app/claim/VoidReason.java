package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A reason a claim on a sheet may be voided with: on the sheet at most once. */
@Entity
@Table(name = "sheet_void_reason")
public class VoidReason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "reason", nullable = false)
    private String reason;

    protected VoidReason() {
    }

    public VoidReason(String sheetId, String reason) {
        this.sheetId = sheetId;
        this.reason = reason;
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
}
