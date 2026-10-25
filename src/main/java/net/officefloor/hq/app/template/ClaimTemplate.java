package net.officefloor.hq.app.template;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** A claim template of the sheet: a claim made from it takes its amount and note at that moment. */
@Entity
@Table(name = "claim_template")
public class ClaimTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "note", nullable = false)
    private String note;

    protected ClaimTemplate() {
    }

    public ClaimTemplate(String sheetId, String name, BigDecimal amount, String note) {
        this.sheetId = sheetId;
        this.name = name;
        set(amount, note);
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

    /** Replace the template's amount (kept to two decimals) and note. */
    public void set(BigDecimal amount, String note) {
        this.amount = amount.setScale(2);
        this.note = note;
    }
}
