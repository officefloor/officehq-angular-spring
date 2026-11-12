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

/** A claim made by a person on a sheet. */
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

    @Column(name = "note", nullable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /** Given when the claim is opened and kept once voided; null for a claim never opened. */
    @Column(name = "claim_number")
    private String number;

    /** The due fixed when the claim is settled; null otherwise, or when the grade had no rate then. */
    @Column(name = "settled_due", precision = 14, scale = 2)
    private BigDecimal settledDue;

    /** The user who opened the claim; null for a DRAFT. */
    @Column(name = "opened_by")
    private String openedBy;

    /** The user who last edited the claim; null for one never edited. */
    @Column(name = "edited_by")
    private String editedBy;

    /** Whether the claim is bound by its person's last statement. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The due (as shown, late charge included) the claim was bound to by its person's last statement; null when it had none (n/a). */
    @Column(name = "stated_due", precision = 14, scale = 2)
    private BigDecimal statedDue;

    /** The number of the SETTLED claim of the same person this claim corrects; null unless it is a correction. */
    @Column(name = "corrects")
    private String corrects;

    /** The day the claim is due by: fixed when it is added, from its person's own terms, else their grade's, else the sheet's, on its date. */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    /** The stamp a forward-only rate is matched against; null for the claim's own id (a clone's copy keeps the original's). */
    @Column(name = "rate_stamp")
    private Long rateStamp;

    /** The business days after its date its due-by was fixed with; a holiday added later works the due-by out again with them. */
    @Column(name = "terms", nullable = false)
    private int terms;

    /** The date that decides how the claim's live figures round: its own date, or a correction's corrected claim's. */
    @Column(name = "rounding_date", nullable = false)
    private LocalDate roundingDate;

    /** Its place in its person's list: a claim with a later place is listed first. */
    @Column(name = "listed", nullable = false)
    private long listed;

    /** The date an OPEN claim is deferred until; null when it was never deferred. */
    @Column(name = "deferred_until")
    private LocalDate deferredUntil;

    /** How many proofs the claim has: raised by one at a time. */
    @Column(name = "proofs", nullable = false)
    private int proofs;

    /** How many of the claim's proofs are photos: the rest are receipts. */
    @Column(name = "photo_proofs", nullable = false)
    private int photoProofs;

    /** The number of the claim this one was split off; null unless it was split off another. */
    @Column(name = "split_from")
    private String splitFrom;

    /** Whether the claim was added while the period its due-by falls in was soft-closed. */
    @Column(name = "late_entry", nullable = false)
    private boolean lateEntry;

    /** The last day (the sheet's today then) a reminder was written for the claim; null when never reminded. */
    @Column(name = "reminded_on")
    private LocalDate remindedOn;

    /** Whether a reminder ever escalated the claim; a claim is escalated at most once. */
    @Column(name = "escalated", nullable = false)
    private boolean escalated;

    /** The day (the sheet's today then) the claim last went to REVIEW; null when it never did. */
    @Column(name = "review_on")
    private LocalDate reviewOn;

    /** The user whose open editor locks the claim against other users' edits; null when not locked. */
    @Column(name = "locked_by")
    private String lockedBy;

    /** The claim's external reference, surrounding spaces removed; null when it has none. Unique on the sheet, regardless of case. */
    @Column(name = "ref")
    private String ref;

    /** The claim's tags: trimmed, lower-cased, without repeats, sorted and joined by commas; null when it has none. */
    @Column(name = "tags")
    private String tags;

    /** The reason the claim was voided with, from the sheet's list; null when voided without one, or not VOID. */
    @Column(name = "void_reason")
    private String voidReason;

    /** The number of the bulk settle that settled the claim (or sent it to PENDING); null when settled one at a time, or not settled. */
    @Column(name = "settle_batch")
    private Long settleBatch;

    /** Whether a steward waived the claim's late charge: it then bears none, whatever its overdue state. */
    @Column(name = "waived", nullable = false)
    private boolean waived;

    /** Whether the claim is held; it still counts in every total. */
    @Column(name = "held", nullable = false)
    private boolean held;

    /** The user a steward assigned the claim's review to; null when no one. It stays once the claim is reviewed. */
    @Column(name = "review_assignee")
    private String reviewAssignee;

    protected Claim() {
    }

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, String note) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.date = date;
        this.roundingDate = date;
        this.note = note;
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

    /** Move the claim to a person on another sheet. */
    public void moveTo(String sheetId, Long personId) {
        this.sheetId = sheetId;
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

    public String getCorrects() {
        return corrects;
    }

    public void setCorrects(String corrects) {
        this.corrects = corrects;
    }

    /** Whether the claim is a correction (a negative amount): its due has no floor and no late charge. */
    public boolean isCorrection() {
        return amount.signum() < 0;
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

    public int getTerms() {
        return terms;
    }

    public void setTerms(int terms) {
        this.terms = terms;
    }

    public LocalDate getRoundingDate() {
        return roundingDate;
    }

    public void setRoundingDate(LocalDate roundingDate) {
        this.roundingDate = roundingDate;
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

    public String getOpenedBy() {
        return openedBy;
    }

    public void setOpenedBy(String openedBy) {
        this.openedBy = openedBy;
    }

    public String getEditedBy() {
        return editedBy;
    }

    public void setEditedBy(String editedBy) {
        this.editedBy = editedBy;
    }

    public boolean isStated() {
        return stated;
    }

    public BigDecimal getStatedDue() {
        return statedDue;
    }

    /**
     * Whether the claim's due follows its statement binding now: any claim its person's last statement bound, a PENDING
     * one included (it shows and totals the bound figure).
     */
    public boolean isBound() {
        return stated;
    }

    /** The due the claim is bound to now; null when it is not bound, or bound with none (n/a). */
    public BigDecimal getBoundDue() {
        return isBound() ? statedDue : null;
    }

    /** Binds the claim to the due (null: n/a), or with {@code stated} false leaves it unbound and live. */
    public void setStated(boolean stated, BigDecimal statedDue) {
        this.stated = stated;
        this.statedDue = stated ? statedDue : null;
    }

    public LocalDate getDeferredUntil() {
        return deferredUntil;
    }

    public void setDeferredUntil(LocalDate deferredUntil) {
        this.deferredUntil = deferredUntil;
    }

    /**
     * Whether the claim is deferred on the date (the sheet's today): an OPEN claim deferred until a later date. It then
     * counts in no total, is not overdue, cannot be settled and is left unbound by a statement.
     */
    public boolean isDeferredOn(LocalDate today) {
        return status == ClaimStatus.OPEN && deferredUntil != null && today != null && today.isBefore(deferredUntil);
    }

    public int getProofs() {
        return proofs;
    }

    /** How many of the claim's proofs are of the kind (receipt or photo). */
    public int proofsOf(String kind) {
        return "photo".equals(kind) ? photoProofs : proofs - photoProofs;
    }

    /** Adds one proof of the kind (receipt or photo) to the claim. */
    public void addProof(String kind) {
        proofs++;
        if ("photo".equals(kind)) {
            photoProofs++;
        }
    }

    public String getSplitFrom() {
        return splitFrom;
    }

    public void setSplitFrom(String splitFrom) {
        this.splitFrom = splitFrom;
    }

    public boolean isLateEntry() {
        return lateEntry;
    }

    public void setLateEntry(boolean lateEntry) {
        this.lateEntry = lateEntry;
    }

    public LocalDate getRemindedOn() {
        return remindedOn;
    }

    public void setRemindedOn(LocalDate remindedOn) {
        this.remindedOn = remindedOn;
    }

    public boolean isEscalated() {
        return escalated;
    }

    public void setEscalated(boolean escalated) {
        this.escalated = escalated;
    }

    public LocalDate getReviewOn() {
        return reviewOn;
    }

    public void setReviewOn(LocalDate reviewOn) {
        this.reviewOn = reviewOn;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public String getRef() {
        return ref;
    }

    public void setRef(String ref) {
        this.ref = ref;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getVoidReason() {
        return voidReason;
    }

    public void setVoidReason(String voidReason) {
        this.voidReason = voidReason;
    }

    public boolean isWaived() {
        return waived;
    }

    public void setWaived(boolean waived) {
        this.waived = waived;
    }

    public boolean isHeld() {
        return held;
    }

    public void setHeld(boolean held) {
        this.held = held;
    }

    public String getReviewAssignee() {
        return reviewAssignee;
    }

    public void setReviewAssignee(String reviewAssignee) {
        this.reviewAssignee = reviewAssignee;
    }

    public Long getSettleBatch() {
        return settleBatch;
    }

    public void setSettleBatch(Long settleBatch) {
        this.settleBatch = settleBatch;
    }

    /**
     * A copy of the claim for a person on another sheet, with its status, frozen figures, dates, note, tags and the
     * numbers it corrects or was split from as they are; it has no number, no statement binding, no settle batch and
     * no editor lock.
     */
    public Claim copyTo(String sheetId, Long personId) {
        Claim copy = new Claim(sheetId, personId, amount, date, note);
        copy.status = status;
        copy.settledDue = settledDue;
        copy.openedBy = openedBy;
        copy.editedBy = editedBy;
        copy.corrects = corrects;
        copy.dueBy = dueBy;
        copy.terms = terms;
        copy.roundingDate = roundingDate;
        copy.deferredUntil = deferredUntil;
        copy.proofs = proofs;
        copy.photoProofs = photoProofs;
        copy.splitFrom = splitFrom;
        copy.lateEntry = lateEntry;
        copy.remindedOn = remindedOn;
        copy.escalated = escalated;
        copy.reviewOn = reviewOn;
        copy.ref = ref;
        copy.tags = tags;
        copy.voidReason = voidReason;
        copy.waived = waived;
        copy.held = held;
        copy.reviewAssignee = reviewAssignee;
        copy.rateStamp = rateStamp();
        return copy;
    }

    /**
     * The stamp a forward-only rate is matched against: the claim sees one saved when the highest claim id was below
     * it, that is one saved before the claim was added.
     */
    public Long rateStamp() {
        return rateStamp != null ? rateStamp : id;
    }

    /** Whether a user other than this one has the claim's editor open. */
    public boolean isLockedAgainst(String user) {
        return lockedBy != null && !lockedBy.equals(user);
    }
}
