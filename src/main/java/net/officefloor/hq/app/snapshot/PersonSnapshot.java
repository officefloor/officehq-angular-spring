package net.officefloor.hq.app.snapshot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A snapshot written for a person when the sheet's today changed: the day being left and their due-total on it. */
@Entity
@Table(name = "person_snapshot")
public class PersonSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "left_on", nullable = false)
    private LocalDate day;

    /** The due-total with exactly two decimals, or "n/a" when the person's grade had no rate then. */
    @Column(name = "due_total", nullable = false)
    private String dueTotal;

    /** The today the sheet moved to as it was written; null for one kept before this was recorded. */
    @Column(name = "written_on")
    private LocalDate writtenOn;

    protected PersonSnapshot() {
    }

    public PersonSnapshot(String sheetId, Long personId, LocalDate day, String dueTotal, LocalDate writtenOn) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.day = day;
        this.dueTotal = dueTotal;
        this.writtenOn = writtenOn;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public Long getPersonId() {
        return personId;
    }

    public LocalDate getDay() {
        return day;
    }

    public String getDueTotal() {
        return dueTotal;
    }

    /** Whether the line had been written as the sheet stood at the end of a date: the sheet had moved on from its day
     *  to a today on or before that date (one kept without that today: its day is before the date). */
    public boolean writtenBy(LocalDate date) {
        return day.isBefore(date) && (writtenOn == null || !writtenOn.isAfter(date));
    }

    /** Move this snapshot to another person, on another sheet (a merge or transfer). */
    public void moveTo(String sheetId, Long personId) {
        this.sheetId = sheetId;
        this.personId = personId;
    }
}
