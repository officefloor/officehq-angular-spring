package net.officefloor.hq.app.holiday;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A holiday on a sheet: a date, on the sheet at most once. */
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

    protected Holiday() {
    }

    public Holiday(String sheetId, LocalDate day) {
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
