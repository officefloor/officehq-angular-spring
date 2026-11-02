package net.officefloor.hq.app.holiday;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A holiday on a sheet: a day no claim falls due on when its due-by is fixed. */
@Entity
@Table(name = "holiday")
public class Holiday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate day;

    /** The sheet's today when the holiday was added. */
    @Column(name = "added_on", nullable = false)
    private LocalDate addedOn;

    protected Holiday() {
    }

    public Holiday(String sheetId, LocalDate day, LocalDate addedOn) {
        this.sheetId = sheetId;
        this.day = day;
        this.addedOn = addedOn;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public LocalDate getDay() {
        return day;
    }

    public LocalDate getAddedOn() {
        return addedOn;
    }
}
