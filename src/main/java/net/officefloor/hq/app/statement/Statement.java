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
 * A statement taken for a person: the sheet's today when it was taken (or the date it was taken as
 * of), their due-total then (none
 * when it was n/a) and how many of their claims counted.
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

    @Column(name = "taken_on", nullable = false)
    private LocalDate takenOn;

    @Column(name = "due_total")
    private BigDecimal dueTotal;

    @Column(name = "claims", nullable = false)
    private int claims;

    /** The sheet's today when the statement was taken; its taken-on is the date it was taken as of. */
    @Column(name = "made_on")
    private LocalDate madeOn;

    protected Statement() {
    }

    public Statement(String sheetId, Long personId, LocalDate takenOn, BigDecimal dueTotal, int claims,
            LocalDate madeOn) {
        this.madeOn = madeOn;
        this.sheetId = sheetId;
        this.personId = personId;
        this.takenOn = takenOn;
        this.dueTotal = dueTotal;
        this.claims = claims;
    }

    /**
     * The statement as a new row for another person (on this or another sheet): its dates, due-total
     * and claims counted kept. Saved after another's, it is listed after theirs.
     */
    public Statement movedTo(String toSheetId, Long toPersonId) {
        return new Statement(toSheetId, toPersonId, takenOn, dueTotal, claims, madeOn);
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

    /** The sheet's today when it was taken; none for one kept from before that was recorded. */
    public LocalDate getMadeOn() {
        return madeOn;
    }

    /** Whether it was taken by the end of a day: the sheet's today then on or before it. */
    public boolean madeBy(LocalDate day) {
        return madeOn == null || !madeOn.isAfter(day);
    }

    public int getClaims() {
        return claims;
    }
}
