package net.officefloor.hq.app.statement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A statement taken for a person: their due-total then (null for n/a), how many claims counted, when, and the date it
 * was taken as of (null: now).
 */
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

    @Column(name = "due_total", precision = 14, scale = 2)
    private BigDecimal dueTotal;

    @Column(name = "claim_count", nullable = false)
    private int claimCount;

    /** The sheet's today when the statement was taken. */
    @Column(name = "taken_on", nullable = false)
    private LocalDate takenOn;

    /** The date the statement was taken as of; null for one taken now. */
    @Column(name = "as_of")
    private LocalDate asOf;

    /** The place the statement stands at in its person's order; null for its own id. */
    @Column(name = "place")
    private Long place;

    protected Statement() {
    }

    public Statement(String sheetId, Long personId, BigDecimal dueTotal, int claimCount, LocalDate takenOn,
            LocalDate asOf) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.dueTotal = dueTotal;
        this.claimCount = claimCount;
        this.takenOn = takenOn;
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

    /** The place the statement stands at in its person's order: the one it was given, else its id. */
    public long getPlace() {
        return place != null ? place : id;
    }

    /** Moves the statement to the person on the sheet, standing at the place in their order. */
    public void moveTo(String sheetId, Long personId, long place) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.place = place;
    }

    public BigDecimal getDueTotal() {
        return dueTotal;
    }

    public int getClaimCount() {
        return claimCount;
    }

    public LocalDate getTakenOn() {
        return takenOn;
    }

    public LocalDate getAsOf() {
        return asOf;
    }

    /** The statement's date: the date it was taken as of, else the sheet's today when it was taken. */
    public LocalDate getDate() {
        return asOf != null ? asOf : takenOn;
    }
}
