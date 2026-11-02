package net.officefloor.hq.app.statement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A statement taken of a person: their due-total on the day it was taken as of, and how many of their
 *  claims counted then. */
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

    /** The sheet's today when the statement was taken. */
    @Column(name = "taken_on", nullable = false)
    private LocalDate takenOn;

    @Column(name = "taken_by", nullable = false)
    private String takenBy;

    /** The person's due-total then; null when it was n/a. */
    @Column(name = "due_total", precision = 14, scale = 2)
    private BigDecimal dueTotal;

    @Column(name = "claims", nullable = false)
    private int claims;

    /** The earlier day the statement was taken as of; null when it was taken as of the sheet's today. */
    @Column(name = "as_of")
    private LocalDate asOf;

    /** The sheet's today when the statement was cancelled; null while it stands. */
    @Column(name = "cancelled_on")
    private LocalDate cancelledOn;

    protected Statement() {
    }

    public Statement(String sheetId, Long personId, LocalDate takenOn, String takenBy, BigDecimal dueTotal,
            int claims, LocalDate asOf) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.takenOn = takenOn;
        this.takenBy = takenBy;
        this.dueTotal = dueTotal;
        this.claims = claims;
        this.asOf = asOf;
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

    public LocalDate getCancelledOn() {
        return cancelledOn;
    }

    /** Cancel the statement on a day (the sheet's today). */
    public void cancel(LocalDate day) {
        this.cancelledOn = day;
    }

    /** Whether the statement stood at the end of a day: taken on or before it and not cancelled by then. */
    public boolean stoodOn(LocalDate day) {
        return !takenOn.isAfter(day) && (cancelledOn == null || cancelledOn.isAfter(day));
    }

    public LocalDate getAsOf() {
        return asOf;
    }

    /** The statement's date: the day it was taken as of, or else the day it was taken. */
    public LocalDate getStatedOn() {
        return asOf != null ? asOf : takenOn;
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
}
