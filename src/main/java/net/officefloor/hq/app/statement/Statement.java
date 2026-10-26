package net.officefloor.hq.app.statement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A statement taken for a person: their due-total then (null for n/a), how many claims counted, and when. */
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

    protected Statement() {
    }

    public Statement(String sheetId, Long personId, BigDecimal dueTotal, int claimCount, LocalDate takenOn) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.dueTotal = dueTotal;
        this.claimCount = claimCount;
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

    public BigDecimal getDueTotal() {
        return dueTotal;
    }

    public int getClaimCount() {
        return claimCount;
    }

    public LocalDate getTakenOn() {
        return takenOn;
    }
}
