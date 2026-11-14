package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A past retirement of a person who was unretired: from the date they were retired from to the
 *  sheet's today when they were unretired. A claim dated after its from and before its to is refused. */
@Entity
@Table(name = "person_retirement")
public class PersonRetirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    @Column(name = "to_date", nullable = false)
    private LocalDate to;

    protected PersonRetirement() {
    }

    public PersonRetirement(String sheetId, Long personId, LocalDate from, LocalDate to) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.from = from;
        this.to = to;
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

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getTo() {
        return to;
    }

    /** Whether a date falls inside the retirement: after its from and before its to. */
    public boolean covers(LocalDate date) {
        return date.isAfter(from) && date.isBefore(to);
    }
}
