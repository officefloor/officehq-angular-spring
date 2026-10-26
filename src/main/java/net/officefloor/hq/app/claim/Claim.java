package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A claim a person makes on a sheet: an amount (two decimals), a date, a note and a status. A negative amount is a
 * correction of the settled claim of the same person whose number it names.
 */
@Entity
@Table(name = "claim")
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "claim_date", nullable = false)
    private LocalDate date;

    @Column(name = "note", nullable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /**
     * The number given when the claim is opened: the sheet's next (1, 2, ...), or while the sheet numbers by period
     * its period's next (P1-1, X-1 for no period); kept when it is voided, never reused; null for a claim never
     * opened.
     */
    @Column(name = "claim_number")
    private String number;

    /** The sequence a period-numbered claim was counted in: its period's name, or X; null otherwise. */
    @Column(name = "number_sequence")
    private String numberSequence;

    /** The claim's place in its number sequence; null unless period-numbered. */
    @Column(name = "number_in_sequence")
    private Long numberInSequence;

    /** The due fixed when the claim was settled; null while it is live. */
    @Column(name = "settled_due", precision = 14, scale = 2)
    private BigDecimal settledDue;

    /** The user who opened the claim; null for a claim never opened. */
    @Column(name = "opened_by")
    private String openedBy;

    /** Whether the claim is bound by its person's last statement. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The figure the claim is bound to by its person's last statement; null for n/a or when not stated. */
    @Column(name = "stated_due", precision = 14, scale = 2)
    private BigDecimal statedDue;

    /** The number of the settled claim a correction corrects; null for a claim that is not a correction. */
    @Column(name = "corrects")
    private String corrects;

    /** The date the claim's rounding follows: its own date, or for a correction the date of the claim it corrects. */
    @Column(name = "rounds_on", nullable = false)
    private LocalDate roundsOn;

    /**
     * The claim's place in the list, newest first: the next place when it is added or moved to another person. The
     * store gives it; a move takes the next through {@link ClaimRepository#relist}.
     */
    @Column(name = "listed", insertable = false, updatable = false)
    private Long listed;

    protected Claim() {
    }

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, String note) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.date = date;
        this.roundsOn = date;
        this.note = note;
    }

    public LocalDate getRoundsOn() {
        return roundsOn;
    }

    public void setRoundsOn(LocalDate roundsOn) {
        this.roundsOn = roundsOn;
    }

    /** Whether the claim is a correction: one with a negative amount. Its due has no floor and no late charge. */
    public boolean isCorrection() {
        return amount.signum() < 0;
    }

    public String getCorrects() {
        return corrects;
    }

    public void setCorrects(String corrects) {
        this.corrects = corrects;
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

    /** Moves the claim to another person of the sheet. */
    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    public Long getListed() {
        return listed;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public void setStatus(ClaimStatus status) {
        this.status = status;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    /** Numbers the claim in the sequence (a period's name, or X) at the place: the number shown is sequence-place. */
    public void numberIn(String sequence, long place) {
        this.number = sequence + "-" + place;
        this.numberSequence = sequence;
        this.numberInSequence = place;
    }

    public BigDecimal getSettledDue() {
        return settledDue;
    }

    public void setSettledDue(BigDecimal settledDue) {
        this.settledDue = settledDue;
    }

    public String getOpenedBy() {
        return openedBy;
    }

    public void setOpenedBy(String openedBy) {
        this.openedBy = openedBy;
    }

    public boolean isStated() {
        return stated;
    }

    /**
     * Whether the claim's figure is bound by its person's last statement: stated, and not pending (a pending claim
     * shows its live figure; rejected, it is bound again).
     */
    public boolean isBound() {
        return stated && status != ClaimStatus.PENDING;
    }

    public BigDecimal getStatedDue() {
        return statedDue;
    }

    /** Binds the claim to the figure (null for n/a) until the next statement. */
    public void bind(BigDecimal figure) {
        this.stated = true;
        this.statedDue = figure;
    }

    /** Frees the claim: its due is live again. */
    public void unbind() {
        this.stated = false;
        this.statedDue = null;
    }
}
