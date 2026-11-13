package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import net.officefloor.hq.app.history.History;

/** A statement taken of a person: the sheet's today then, or the date it was taken as of, their due-total
 *  then (none when it was n/a) and how many of their claims counted. */
@Entity
@EntityListeners(History.class)
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

    @Column(name = "claim_count", nullable = false)
    private int claimCount;

    protected Statement() {
    }

    public Statement(String sheetId, Long personId, LocalDate takenOn, BigDecimal dueTotal, int claimCount) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.takenOn = takenOn;
        this.dueTotal = dueTotal;
        this.claimCount = claimCount;
    }

    /** The same statement, taken of another person (on this sheet or another). */
    public Statement copyTo(String otherSheetId, Long otherPersonId) {
        return new Statement(otherSheetId, otherPersonId, takenOn, dueTotal, claimCount);
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

    public int getClaimCount() {
        return claimCount;
    }
}
