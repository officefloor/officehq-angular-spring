package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A statement taken of a person: their due-total then (null when n/a), how many of their claims
 *  counted then, the sheet's today when it was taken, and the date it was taken as of (none: as the
 *  sheet stood when it was taken). */
@Entity
@Table(name = "person_statement")
public class PersonStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "due_total", precision = 15, scale = 2)
    private BigDecimal dueTotal;

    @Column(name = "claims", nullable = false)
    private int claims;

    @Column(name = "taken_on", nullable = false)
    private LocalDate takenOn;

    @Column(name = "as_of")
    private LocalDate asOf;

    @Column(name = "taken_by", nullable = false)
    private String takenBy;

    protected PersonStatement() {
    }

    public PersonStatement(String sheetId, Long personId, BigDecimal dueTotal, int claims, LocalDate takenOn,
            LocalDate asOf, String takenBy) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.dueTotal = dueTotal;
        this.claims = claims;
        this.takenOn = takenOn;
        this.asOf = asOf;
        this.takenBy = takenBy;
    }

    public Long getId() {
        return id;
    }

    public Long getPersonId() {
        return personId;
    }

    public BigDecimal getDueTotal() {
        return dueTotal;
    }

    public int getClaims() {
        return claims;
    }

    public LocalDate getTakenOn() {
        return takenOn;
    }

    /** The date the statement was taken as of; null when it was taken as the sheet stood then. */
    public LocalDate getAsOf() {
        return asOf;
    }

    /** The date the statement shows: the one it was taken as of, else the sheet's today when taken. */
    public LocalDate getStatedOn() {
        return asOf != null ? asOf : takenOn;
    }
}
