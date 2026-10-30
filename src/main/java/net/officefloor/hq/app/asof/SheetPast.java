package net.officefloor.hq.app.asof;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** Every row of a sheet as it stood at the end of a day it had as its today, kept as JSON; see {@link SheetPasts}. */
@Entity
@Table(name = "sheet_past")
public class SheetPast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    /** The sheet's today that was left when it was written. */
    @Column(name = "ended_on", nullable = false)
    private LocalDate endedOn;

    @Lob
    @Column(name = "state", nullable = false)
    private String state;

    protected SheetPast() {
    }

    public SheetPast(String sheetId, LocalDate endedOn, String state) {
        this.sheetId = sheetId;
        this.endedOn = endedOn;
        this.state = state;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public LocalDate getEndedOn() {
        return endedOn;
    }

    public String getState() {
        return state;
    }
}
