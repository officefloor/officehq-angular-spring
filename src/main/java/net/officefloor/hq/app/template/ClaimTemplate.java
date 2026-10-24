package net.officefloor.hq.app.template;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** A claim template on a sheet: a name, and the amount and note a claim added from it is given. */
@Entity
@Table(name = "sheet_claim_template")
public class ClaimTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "note", nullable = false)
    private String note;

    protected ClaimTemplate() {
    }

    public ClaimTemplate(String sheetId, String name) {
        this.sheetId = sheetId;
        this.name = name;
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

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
