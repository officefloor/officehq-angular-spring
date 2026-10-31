package net.officefloor.hq.app.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** What a sheet held after a change made while its today was {@code asOf}, as JSON (see {@link SheetState}). */
@Entity
@Table(name = "sheet_snapshot")
public class SheetSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "as_of", nullable = false)
    private LocalDate asOf;

    @Lob
    @Column(name = "state", nullable = false)
    private String state;

    protected SheetSnapshot() {
    }

    public SheetSnapshot(String sheetId, LocalDate asOf, String state) {
        this.sheetId = sheetId;
        this.asOf = asOf;
        this.state = state;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public LocalDate getAsOf() {
        return asOf;
    }

    public String getState() {
        return state;
    }
}
