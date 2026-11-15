package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A claim number a re-dated claim left behind as it moved to another period: never given again. */
@Entity
@Table(name = "retired_number")
public class RetiredNumber {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "claim_number", nullable = false)
    private String number;

    protected RetiredNumber() {
    }

    public RetiredNumber(String sheetId, String number) {
        this.sheetId = sheetId;
        this.number = number;
    }

    public String getNumber() {
        return number;
    }
}
