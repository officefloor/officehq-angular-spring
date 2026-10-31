package net.officefloor.hq.app.voidreason;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A void reason on a sheet: while its list is not empty, a claim is voided with one of them. */
@Entity
@Table(name = "void_reason")
public class VoidReason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "reason", nullable = false, length = 200)
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
