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

/** A claim a person on a sheet makes: an amount (two decimals), a date, a note and a status (see
 * {@link ClaimStatus}). A claim with a negative amount is a correction of the SETTLED claim of the
 * same person whose number it names in {@link #corrects}. */
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

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "claim_date", nullable = false)
    private LocalDate date;

    /** The date the claim is due by, fixed when it is added: its date plus the terms of its person's
     * grade in force on that date. Later changes to the terms or to its date leave it as it is. */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    @Column(name = "note", nullable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /** The claim's number on its sheet, given when it is opened and kept once VOID; null while
     * DRAFT (and on a claim voided as a draft). */
    @Column(name = "claim_number")
    private String number;

    /** What was due on the claim when it was settled; null until then, and when there was no rate. */
    @Column(name = "settled_due", precision = 12, scale = 2)
    private BigDecimal settledDue;

    /** The user who opened the claim; null until it is opened. */
    @Column(name = "opened_by")
    private String openedBy;

    /** Whether the claim is bound to {@link #statedDue}, the figure it had at its person's last
     * statement that it counted in. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The figure the claim was bound to at its person's last statement; null while unbound, and when
     * it had no figure (no rate) then. */
    @Column(name = "stated_due", precision = 12, scale = 2)
    private BigDecimal statedDue;

    /** The number of the SETTLED claim of the same person this correction corrects; null on a claim
     * that is not a correction. */
    @Column(name = "corrects")
    private String corrects;

    /** The claim's place in its sheet's list, newest (highest) first; given anew when it moves to
     * another person. */
    @Column(name = "listed", nullable = false)
    private long listed;

    /** The date the claim is deferred until; null when it was never deferred. While it is OPEN and the
     * sheet's today is before this date it is deferred (see {@link #isDeferredOn}). */
    @Column(name = "deferred_until")
    private LocalDate deferredUntil;

    /** How many proofs the claim has been given; raised by one at a time while it is neither SETTLED nor VOID. */
    @Column(name = "proofs", nullable = false)
    private int proofs;

    /** The number of the claim this one was split off; null on a claim that was not split off another. */
    @Column(name = "split_from")
    private String splitFrom;

    /** Whether the claim was added while the period its date falls in was soft-closed. */
    @Column(name = "late_entry", nullable = false)
    private boolean lateEntry;

    /** The user whose open editor locks the claim for everyone else; null while no one holds it. */
    @Column(name = "locked_by")
    private String lockedBy;

    /** The sheet's today when {@link #lockedBy} took the lock: it holds only while that is still the
     * sheet's today. */
    @Column(name = "locked_on")
    private LocalDate lockedOn;

    protected Claim() {
    }

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, String note, String corrects) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.date = date;
        this.note = note;
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

    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    public long getListed() {
        return listed;
    }

    public void setListed(long listed) {
        this.listed = listed;
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

    public LocalDate getDueBy() {
        return dueBy;
    }

    public void setDueBy(LocalDate dueBy) {
        this.dueBy = dueBy;
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

    public BigDecimal getSettledDue() {
        return settledDue;
    }

    public void setSettledDue(BigDecimal settledDue) {
        this.settledDue = settledDue;
    }

    public boolean isStated() {
        return stated;
    }

    public BigDecimal getStatedDue() {
        return statedDue;
    }

    /** Bind the claim to {@code due}, the figure it has now. */
    public void bind(BigDecimal due) {
        this.stated = true;
        this.statedDue = due;
    }

    /** Leave the claim unbound: its figure follows the rates again. */
    public void unbind() {
        this.stated = false;
        this.statedDue = null;
    }

    public String getOpenedBy() {
        return openedBy;
    }

    public void setOpenedBy(String openedBy) {
        this.openedBy = openedBy;
    }

    public String getCorrects() {
        return corrects;
    }

    public void setCorrects(String corrects) {
        this.corrects = corrects;
    }

    /** Whether the claim is a correction: one with a negative amount. */
    public boolean isCorrection() {
        return amount.signum() < 0;
    }

    public boolean isLateEntry() {
        return lateEntry;
    }

    public void setLateEntry(boolean lateEntry) {
        this.lateEntry = lateEntry;
    }

    public String getSplitFrom() {
        return splitFrom;
    }

    public void setSplitFrom(String splitFrom) {
        this.splitFrom = splitFrom;
    }

    public LocalDate getDeferredUntil() {
        return deferredUntil;
    }

    public void setDeferredUntil(LocalDate deferredUntil) {
        this.deferredUntil = deferredUntil;
    }

    public int getProofs() {
        return proofs;
    }

    /** Raise the claim's proofs count by one. */
    public void addProof() {
        this.proofs++;
    }

    /** Whether the claim is deferred on {@code today}: OPEN and deferred until a date after it. */
    public boolean isDeferredOn(LocalDate today) {
        return status == ClaimStatus.OPEN && deferredUntil != null && today != null && today.isBefore(deferredUntil);
    }

    /** Whether a user other than {@code user} holds the claim's lock on {@code today}. */
    public boolean isLockedFor(String user, LocalDate today) {
        return lockedBy != null && !lockedBy.equals(user) && today.equals(lockedOn);
    }

    /** Lock the claim for everyone but {@code user} while {@code today} is the sheet's today. */
    public void lock(String user, LocalDate today) {
        this.lockedBy = user;
        this.lockedOn = today;
    }

    /** Release the claim's lock, whoever holds it. */
    public void unlock() {
        this.lockedBy = null;
        this.lockedOn = null;
    }

    public String getLockedBy() {
        return lockedBy;
    }
}
