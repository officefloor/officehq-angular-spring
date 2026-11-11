package net.officefloor.hq.app.statement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A statement taken of a person: their due-total and how many of their claims counted, as of the date it was taken as
 * of (the sheet's today, unless an earlier date was given).
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

    /** The date the statement was taken as of. */
    @Column(name = "taken_on", nullable = false)
    private LocalDate takenOn;

    /** The sheet's today when the statement was taken; null for one taken before that was kept. */
    @Column(name = "made_on")
    private LocalDate madeOn;

    /** Two decimals, or n/a when the person's due-total had none. */
    @Column(name = "due_total", nullable = false)
    private String dueTotal;

    @Column(name = "claims", nullable = false)
    private int claims;

    protected Statement() {
    }

    public Statement(String sheetId, Long personId, LocalDate takenOn, LocalDate madeOn, String dueTotal, int claims) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.takenOn = takenOn;
        this.madeOn = madeOn;
        this.dueTotal = dueTotal;
        this.claims = claims;
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

    public LocalDate getTakenOn() {
        return takenOn;
    }

    public LocalDate getMadeOn() {
        return madeOn;
    }

    /** Whether the statement was taken while the sheet's today was on or before the date. */
    public boolean madeBy(LocalDate date) {
        return madeOn == null || !madeOn.isAfter(date);
    }

    public String getDueTotal() {
        return dueTotal;
    }

    public int getClaims() {
        return claims;
    }
}
