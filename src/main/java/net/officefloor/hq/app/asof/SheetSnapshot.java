package net.officefloor.hq.app.asof;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A state a sheet stood in after a change to it: every row kept for the sheet, as then (see {@link SheetState}), with
 * the sheet's today then.
 */
@Entity
@Table(name = "sheet_snapshot")
public class SheetSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "made_on", nullable = false)
    private LocalDate madeOn;

    @Column(name = "digest", nullable = false)
    private String digest;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "data", nullable = false)
    private byte[] data;

    protected SheetSnapshot() {
    }

    public SheetSnapshot(String sheetId, LocalDate madeOn, String digest, byte[] data) {
        this.sheetId = sheetId;
        this.madeOn = madeOn;
        this.digest = digest;
        this.data = data;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public LocalDate getMadeOn() {
        return madeOn;
    }

    public String getDigest() {
        return digest;
    }

    public byte[] getData() {
        return data;
    }
}
