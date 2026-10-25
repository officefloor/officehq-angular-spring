package net.officefloor.hq.app.rollup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A person's due-total as it stood on the day the sheet's today left: one per SNAPSHOT written for them. */
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

    /** The day being left. */
    @Column(name = "left_on", nullable = false)
    private LocalDate day;

    /** The person's due-total on that day; null when it was n/a. */
    @Column(name = "due_total")
    private BigDecimal dueTotal;

    protected PersonSnapshot() {
    }

    public PersonSnapshot(String sheetId, Long personId, LocalDate day, BigDecimal dueTotal) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.day = day;
        this.dueTotal = dueTotal;
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
}
