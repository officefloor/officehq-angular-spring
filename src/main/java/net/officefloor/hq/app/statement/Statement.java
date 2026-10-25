package net.officefloor.hq.app.statement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A statement taken of a person: their due-total at that moment, or as of the date it was taken as of,
 *  and how many claims counted. */
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

    /** The person's due-total when the statement was taken; none when it was n/a. */
    @Column(name = "due_total", precision = 12, scale = 2)
    private BigDecimal dueTotal;

    @Column(name = "claims", nullable = false)
    private int claims;

    /** The sheet's today when the statement was taken. */
    @Column(name = "taken_on", nullable = false)
    private LocalDate takenOn;

    /** The statement's date: the date it was taken as of, else the sheet's today when it was taken. */
    @Column(name = "stated_on", nullable = false)
    private LocalDate statedOn;

    @Column(name = "taken_by", nullable = false)
    private String takenBy;

    /** Whether a merge or a transfer carried the statement to the person from another: it counts among
     *  their statements, but is their last only when they have none of their own. */
    @Column(name = "carried", nullable = false)
    private boolean carried;

    protected Statement() {
    }

    public Statement(String sheetId, Long personId, BigDecimal dueTotal, int claims, LocalDate takenOn,
            LocalDate statedOn, String takenBy) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.dueTotal = dueTotal;
        this.claims = claims;
        this.takenOn = takenOn;
        this.statedOn = statedOn;
        this.takenBy = takenBy;
    }

    /** Carry the statement to the person a merge folds its person into; carried unless that person has
     *  no statement of their own, when it stays as it was. */
    public void carryTo(Long otherPersonId, boolean asCarried) {
        this.personId = otherPersonId;
        this.carried = carried || asCarried;
    }

    /** A copy of the statement for a person on another sheet, as a transfer takes it. */
    public Statement copyTo(String otherSheetId, Long otherPersonId) {
        Statement copy = new Statement(otherSheetId, otherPersonId, dueTotal, claims, takenOn, statedOn, takenBy);
        copy.carried = carried;
        return copy;
    }

    public boolean isCarried() {
        return carried;
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

    public BigDecimal getDueTotal() {
        return dueTotal;
    }

    public int getClaims() {
        return claims;
    }

    public LocalDate getTakenOn() {
        return takenOn;
    }

    public LocalDate getStatedOn() {
        return statedOn;
    }

    public String getTakenBy() {
        return takenBy;
    }
}
