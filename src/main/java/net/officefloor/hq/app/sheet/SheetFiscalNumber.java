package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The last claim number a sheet gave within a fiscal year (named by the calendar year its first
 *  day falls in). */
@Entity
@Table(name = "sheet_fiscal_number")
public class SheetFiscalNumber {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "fiscal_year", nullable = false)
    private int fiscalYear;

    @Column(name = "last_number", nullable = false)
    private int lastNumber;

    protected SheetFiscalNumber() {
    }

    public SheetFiscalNumber(String sheetId, int fiscalYear) {
        this.sheetId = sheetId;
        this.fiscalYear = fiscalYear;
    }

    public int getLastNumber() {
        return lastNumber;
    }

    /** Take the next number after {@code last}, the last given in the fiscal year anywhere in the sheet's tree. */
    public int nextNumberAfter(int last) {
        lastNumber = Math.max(lastNumber, last) + 1;
        return lastNumber;
    }
}
