package net.officefloor.hq.app.holiday;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A holiday on a sheet: a claim whose due-by, when fixed at its creation, falls on it is due by the next day that
 *  is not a holiday. */
@Entity
@Table(name = "holiday")
public class Holiday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate date;

    protected Holiday() {
    }

    public Holiday(String sheetId, LocalDate date) {
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
