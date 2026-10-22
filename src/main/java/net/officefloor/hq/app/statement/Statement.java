package net.officefloor.hq.app.statement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A statement taken for a person: its number among theirs, its due-total (null when n/a) and date. */
@Entity
@Table(name = "person_statement")
public class Statement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "total", precision = 14, scale = 2)
    private BigDecimal total;

    @Column(name = "taken_on", nullable = false)
    private LocalDate takenOn;

    protected Statement() {
    }

    public Statement(String sheetId, Long personId, int seq, BigDecimal total, LocalDate takenOn) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.seq = seq;
        this.total = total;
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

    public int getSeq() {
        return seq;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public LocalDate getTakenOn() {
        return takenOn;
    }

    /** Move the statement to a person (on a sheet) under a number among theirs. */
    public void moveTo(String sheetId, Long personId, int seq) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.seq = seq;
    }
}
