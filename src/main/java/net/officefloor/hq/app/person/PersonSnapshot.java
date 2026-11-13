package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A snapshot of a person written as the sheet's today moved on: the day being left and their due-total
 *  then (none when it was n/a), and the today it moved to. */
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

    @Column(name = "due_total")
    private BigDecimal dueTotal;

    /** The sheet's today the snapshot was taken into; none for one kept before this was. */
    @Column(name = "taken_on")
    private LocalDate takenOn;

    protected PersonSnapshot() {
    }

    public PersonSnapshot(String sheetId, Long personId, LocalDate day, BigDecimal dueTotal, LocalDate takenOn) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.day = day;
        this.dueTotal = dueTotal;
        this.takenOn = takenOn;
    }

    /** The same snapshot, of another person (on this sheet or another). */
    public PersonSnapshot copyTo(String otherSheetId, Long otherPersonId) {
        return new PersonSnapshot(otherSheetId, otherPersonId, day, dueTotal, takenOn);
    }

    /** Whether the snapshot was taken by the end of a date: by the day change into a today on or before it
     *  (one kept before that was recorded, by its day being left being before it). */
    public boolean takenBy(LocalDate date) {
        return takenOn == null ? day.isBefore(date) : !takenOn.isAfter(date);
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

    public BigDecimal getDueTotal() {
        return dueTotal;
    }

    /** The line the screen shows: the day being left, then the due-total (n/a when it was). */
    public String line() {
        return day + " " + (dueTotal == null ? "n/a" : dueTotal.toPlainString());
    }
}
