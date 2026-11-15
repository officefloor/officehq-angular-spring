package net.officefloor.hq.app.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A void reason on the sheet's list: while the list is not empty, a claim is voided only with one of them. */
@Entity
@Table(name = "sheet_void_reason")
public class SheetVoidReason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "reason", nullable = false)
    private String reason;

    protected SheetVoidReason() {
    }

    public SheetVoidReason(String sheetId, String reason) {
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
