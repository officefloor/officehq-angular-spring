package net.officefloor.hq.app.statement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A statement taken for a person: the sheet's today when it was taken, their due-total then (two
 *  decimals, or n/a) and how many of their claims counted. */
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

    @Column(name = "taken_on", nullable = false)
    private LocalDate takenOn;

    @Column(name = "due_total", nullable = false)
    private String dueTotal;

    @Column(name = "claims", nullable = false)
    private int claims;

    protected Statement() {
    }

    public Statement(String sheetId, Long personId, LocalDate takenOn, String dueTotal, int claims) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.takenOn = takenOn;
        this.dueTotal = dueTotal;
        this.claims = claims;
    }

    public Long getId() {
        return id;
    }

    public Long getPersonId() {
        return personId;
    }

    public LocalDate getTakenOn() {
        return takenOn;
    }

    public String getDueTotal() {
        return dueTotal;
    }

    public int getClaims() {
        return claims;
    }
}
