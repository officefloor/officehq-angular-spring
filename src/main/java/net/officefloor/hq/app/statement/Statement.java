package net.officefloor.hq.app.statement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A statement taken of a person: the sheet's today when it was taken, the person's due-total then
 * (two decimals) and how many of their claims counted toward it. */
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

    @Column(name = "statement_date", nullable = false)
    private LocalDate date;

    @Column(name = "due_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal dueTotal;

    @Column(name = "claim_count", nullable = false)
    private int claimCount;

    protected Statement() {
    }

    public Statement(String sheetId, Long personId, LocalDate date, BigDecimal dueTotal, int claimCount) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.date = date;
        this.dueTotal = dueTotal;
        this.claimCount = claimCount;
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

    public LocalDate getDate() {
        return date;
    }

    public BigDecimal getDueTotal() {
        return dueTotal;
    }

    public int getClaimCount() {
        return claimCount;
    }
}
