package net.officefloor.hq.app.statement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A statement taken for a person: the sheet's today when it was taken, their due-total then (two
 *  decimals, or n/a) and how many of their claims counted. */
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

    @Column(name = "taken_on", nullable = false)
    private LocalDate takenOn;

    @Column(name = "due_total", nullable = false)
    private String dueTotal;

    @Column(name = "claims", nullable = false)
    private int claims;

    /** The sheet's today when the statement was taken; null for one taken before it was kept. */
    @Column(name = "made_on", updatable = false)
    private LocalDate madeOn;

    /** The sheet's today when the statement was cancelled; null while it stands. */
    @Column(name = "cancelled_on")
    private LocalDate cancelledOn;

    /** Where the statement falls among its person's statements; null: by its id. */
    @Column(name = "ordinal")
    private Long ordinal;

    protected Statement() {
    }

    public Statement(String sheetId, Long personId, LocalDate takenOn, String dueTotal, int claims, LocalDate madeOn) {
        this.madeOn = madeOn;
        this.sheetId = sheetId;
        this.personId = personId;
        this.takenOn = takenOn;
        this.dueTotal = dueTotal;
        this.claims = claims;
    }

    public Long getId() {
        return id;
    }

    public Long getPersonId() {
        return personId;
    }

    /** Where the statement falls among its person's statements: the last is the highest. */
    public long getOrder() {
        return ordinal != null ? ordinal : id;
    }

    /** Move the statement to another person, falling at an order among their statements. */
    public void moveTo(Long personId, long order) {
        this.personId = personId;
        this.ordinal = order;
    }

    public LocalDate getTakenOn() {
        return takenOn;
    }

    public String getDueTotal() {
        return dueTotal;
    }

    /** Whether the statement stood at the end of a day: taken while the sheet's today was on or before
     *  it (one taken before that was kept: dated on or before it). */
    public boolean madeAsOf(LocalDate day) {
        return !(madeOn != null ? madeOn : takenOn).isAfter(day);
    }

    /** Whether the statement stands now: it was not cancelled. */
    public boolean isLive() {
        return cancelledOn == null;
    }

    /** Whether the statement stood at the end of a day: made as of it, and not cancelled while the
     *  sheet's today was on or before it. */
    public boolean stoodAsOf(LocalDate day) {
        return madeAsOf(day) && (cancelledOn == null || cancelledOn.isAfter(day));
    }

    /** Cancel the statement on the sheet's today. */
    public void cancel(LocalDate today) {
        this.cancelledOn = today;
    }

    public int getClaims() {
        return claims;
    }
}
