package net.officefloor.hq.app.holiday;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A holiday on a sheet: a date no claim added from then on is due by. */
@Entity
@Table(name = "sheet_holiday")
public class SheetHoliday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate date;

    protected SheetHoliday() {
    }

    public SheetHoliday(String sheetId, LocalDate date) {
        this.sheetId = sheetId;
        this.date = date;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public LocalDate getDate() {
        return date;
    }
}
