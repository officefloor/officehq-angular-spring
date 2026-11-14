package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A SNAPSHOT written for a person: the day the sheet's today was leaving, and their due-total then
 *  as the audit shows it ("n/a" when none), and the day the sheet's today moved to. */
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

    @Column(name = "snapshot_day", nullable = false)
    private LocalDate day;

    @Column(name = "due_total", nullable = false)
    private String dueTotal;

    @Column(name = "made_on")
    private LocalDate madeOn;

    protected PersonSnapshot() {
    }

    public PersonSnapshot(String sheetId, Long personId, LocalDate day, String dueTotal, LocalDate madeOn) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.day = day;
        this.dueTotal = dueTotal;
        this.madeOn = madeOn;
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

    /** Whether the snapshot had been written by the end of a day: the sheet's today had moved to the
     *  day it was written on by then (one kept without that day: the day after its own). */
    public boolean writtenBy(LocalDate asOf) {
        return madeOn != null ? !madeOn.isAfter(asOf) : day.isBefore(asOf);
    }
}
