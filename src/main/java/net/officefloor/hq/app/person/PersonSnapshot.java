package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A snapshot line of a person: the day being left when it was written and their due-total on it as shown. */
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

    protected PersonSnapshot() {
    }

    public PersonSnapshot(String sheetId, Long personId, LocalDate day, String dueTotal) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.day = day;
        this.dueTotal = dueTotal;
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
}
