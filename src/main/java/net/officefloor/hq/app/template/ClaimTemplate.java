package net.officefloor.hq.app.template;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** A claim template of the sheet, each name once: the amount and note a claim made from it takes. */
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

    @Column(name = "note", nullable = false, length = 500)
    private String note;

    protected ClaimTemplate() {}

    public ClaimTemplate(String sheetId, String name, BigDecimal amount, String note) {
        this.sheetId = sheetId;
        this.name = name;
        this.amount = amount;
        this.note = note;
    }

    public Long getId() { return id; }

    public String getSheetId() { return sheetId; }

    public String getName() { return name; }

    public BigDecimal getAmount() { return amount; }

    public String getNote() { return note; }

    /** Replace the amount and note: claims made from it before keep theirs. */
    public void replace(BigDecimal amount, String note) {
        this.amount = amount;
        this.note = note;
    }
}
