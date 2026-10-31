package net.officefloor.hq.app.claimtemplate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** A claim template on a sheet: a name, an amount and a note a person's row adds a DRAFT claim from. */
@Entity
@Table(name = "claim_template")
public class ClaimTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "note", nullable = false, length = 1000)
    private String note;

    protected ClaimTemplate() {
    }

    public ClaimTemplate(String sheetId, String name, BigDecimal amount, String note) {
        this.sheetId = sheetId;
        this.name = name;
        this.amount = amount;
        this.note = note;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getNote() {
        return note;
    }

    /** Replace the template's amount and note, as saving its name again does. */
    public void replace(BigDecimal amount, String note) {
        this.amount = amount;
        this.note = note;
    }
}
