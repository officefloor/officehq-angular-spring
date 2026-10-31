package net.officefloor.hq.app.statement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A statement taken of a person: their due-total at that moment and the sheet's today then. */
@Entity
@Table(name = "statement")
public class Statement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    /** The due-total with exactly two decimals, or "n/a" when the person's grade had no rate then. */
    @Column(name = "due_total", nullable = false)
    private String dueTotal;

    @Column(name = "taken_on", nullable = false)
    private LocalDate takenOn;

    protected Statement() {
    }

    public Statement(String sheetId, Long personId, String dueTotal, LocalDate takenOn) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.dueTotal = dueTotal;
        this.takenOn = takenOn;
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

    public String getDueTotal() {
        return dueTotal;
    }

    public LocalDate getTakenOn() {
        return takenOn;
    }
}
