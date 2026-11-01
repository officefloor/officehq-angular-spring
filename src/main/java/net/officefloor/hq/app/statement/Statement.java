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
 * A statement taken for a person on a sheet: their due-total at that moment (none when it was n/a),
 * the sheet's today when it was taken, and who took it.
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

    @Column(name = "statement_date", nullable = false)
    private LocalDate date;

    @Column(name = "taken_by", nullable = false, length = 100)
    private String takenBy;

    protected Statement() {}

    public Statement(String sheetId, Long personId, BigDecimal dueTotal, LocalDate date, String takenBy) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.dueTotal = dueTotal;
        this.date = date;
        this.takenBy = takenBy;
    }

    public Long getId() { return id; }

    public String getSheetId() { return sheetId; }

    public Long getPersonId() { return personId; }

    public BigDecimal getDueTotal() { return dueTotal; }

    public LocalDate getDate() { return date; }

    public String getTakenBy() { return takenBy; }
}
