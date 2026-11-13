package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A sheet, identified by the id a user types. Everything else in the app is kept per sheet. */
@Entity
@Table(name = "sheet")
public class Sheet {

    @Id
    private String id;

    /** The sheet's today: every date-dependent decision and default date on the sheet uses it. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last claim number given on the sheet; the next claim opened takes the one after. */
    @Column(name = "last_claim_number", nullable = false)
    private long lastClaimNumber;

    protected Sheet() {
    }

    public Sheet(String id, LocalDate today) {
        this.id = id;
        this.today = today;
    }

    public String getId() {
        return id;
    }

    public LocalDate getToday() {
        return today;
    }

    public void setToday(LocalDate today) {
        this.today = today;
    }

    /** Take the sheet's next claim number. */
    public long nextClaimNumber() {
        return ++lastClaimNumber;
    }
}
