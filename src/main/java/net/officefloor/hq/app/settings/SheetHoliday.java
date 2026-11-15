package net.officefloor.hq.app.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A holiday on the sheet: a claim's due-by, as it is fixed at creation, is never one. */
@Entity
@Table(name = "sheet_holiday")
public class SheetHoliday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate day;

    protected SheetHoliday() {
    }

    public SheetHoliday(String sheetId, LocalDate day) {
        this.sheetId = sheetId;
        this.day = day;
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
}
