package net.officefloor.hq.app.statement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A statement taken for a person: their due-total then (none when it was n/a), how many of their claims
 * counted, the sheet's today when it was taken and, for one taken as of an earlier date, that date. */
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

    @Column(name = "taken_by", nullable = false)
    private String takenBy;

    @Column(name = "due_total", precision = 14, scale = 2)
    private BigDecimal dueTotal;

    @Column(name = "claims", nullable = false)
    private int claims;

    /** The date the statement was taken as of; none when it was taken as of the sheet's today then. */
    @Column(name = "as_of")
    private LocalDate asOf;

    protected Statement() {}

    public Statement(String sheetId, Long personId, LocalDate takenOn, String takenBy, BigDecimal dueTotal,
            int claims, LocalDate asOf) {
        this.asOf = asOf;
        this.sheetId = sheetId;
        this.personId = personId;
        this.takenOn = takenOn;
        this.takenBy = takenBy;
        this.dueTotal = dueTotal;
        this.claims = claims;
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

    public String getTakenBy() {
        return takenBy;
    }

    public BigDecimal getDueTotal() {
        return dueTotal;
    }

    public int getClaims() {
        return claims;
    }

    public LocalDate getAsOf() {
        return asOf;
    }

    /** The statement's date: the date it was taken as of, else the sheet's today when it was taken. */
    public LocalDate getStatementDate() {
        return asOf != null ? asOf : takenOn;
    }
}
