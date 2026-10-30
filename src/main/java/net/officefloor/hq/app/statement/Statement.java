package net.officefloor.hq.app.statement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A statement taken for a person: the sheet's today then, their due-total then and how many claims counted. */
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

    /** The person's due-total when taken; null when it was n/a. */
    @Column(name = "due_total", precision = 14, scale = 2)
    private BigDecimal dueTotal;

    @Column(name = "claims", nullable = false)
    private int claims;

    protected Statement() {
    }

    public Statement(String sheetId, Long personId, LocalDate takenOn, BigDecimal dueTotal, int claims) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.takenOn = takenOn;
        this.dueTotal = dueTotal;
        this.claims = claims;
    }

    /** The statement as carried to another person (on the same or another sheet), as it was taken. */
    public Statement carried(String sheetId, Long personId) {
        return new Statement(sheetId, personId, takenOn, dueTotal, claims);
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

    public LocalDate getTakenOn() {
        return takenOn;
    }

    public BigDecimal getDueTotal() {
        return dueTotal;
    }

    public int getClaims() {
        return claims;
    }
}
