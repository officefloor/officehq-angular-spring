package net.officefloor.hq.app.claim;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.history.HistoryListener;

/** A claim made by a person on a sheet: an amount, a date, a note and its status. */
@Entity
@EntityListeners(HistoryListener.class)
@Table(name = "claim")
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "claim_date", nullable = false)
    private LocalDate date;

    /** The date the claim is due by: fixed when it is created, its date plus its person's own
     *  terms, else the terms of their grade, else the sheet's, each as in force on the sheet's today
     *  when it is created (a split-off part keeps the terms of the claim it is split from); later
     *  changes to the terms or its date leave it. A holiday added on or before it re-fixes it (while
     *  the claim is neither SETTLED nor VOID) from its date with {@link #terms}. */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    /** The terms (business days) the claim's due-by was fixed with. */
    @Column(name = "terms", nullable = false)
    private int terms;

    @Column(name = "note", nullable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /** The sheet's number for the claim, given when it is settled (or approved) and kept if it is
     *  voided; none while DRAFT, OPEN, PENDING or REVIEW (a claim numbered when opened, before
     *  numbering moved to settlement, keeps its number). A sheet-wide number (1, 2, ...), or under period numbering one within its
     *  period (<period name>-<n>, X-<n> in no period). */
    @Column(name = "claim_number")
    private String number;

    /** The due fixed when the claim was settled; none before then, or when its grade had no rate
     *  (its due then stays live). */
    @Column(name = "settled_due", precision = 15, scale = 2)
    private BigDecimal settledDue;

    /** The user who opened the claim; none while DRAFT. That user cannot settle it. */
    @Column(name = "opened_by")
    private String openedBy;

    /** The user who last edited the claim; none until edited. Like its opener, that user cannot
     *  settle or approve it. */
    @Column(name = "edited_by")
    private String editedBy;

    /** The number of the bulk settle that settled the claim (or sent it to PENDING); none when
     *  it was settled one at a time or not at all. */
    @Column(name = "batch")
    private Integer batch;

    /** Whether the person's last statement bound the claim (it counted then); its due then stays
     *  {@link #statedDue} until their next statement. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The due the claim showed at the statement that bound it; none while unbound, or when it was
     *  n/a then. */
    @Column(name = "stated_due", precision = 15, scale = 2)
    private BigDecimal statedDue;

    /** The number of the SETTLED claim of the same person that this correction (a negative
     *  amount) corrects; none for any other claim. */
    @Column(name = "corrects")
    private String corrects;

    /** Where the claim is listed, newest (highest) first: the sheet's next when it is added or
     *  reassigned to another person. */
    @Column(name = "listed", nullable = false)
    private long listed;

    /** The date an OPEN claim is deferred until; none when it was never deferred. */
    @Column(name = "deferred_until")
    private LocalDate deferredUntil;

    /** How many proofs the claim has; 0 until the first is added. */
    @Column(name = "proofs", nullable = false)
    private int proofs;

    /** Of the claim's proofs, how many are photos; the rest are receipts. */
    @Column(name = "photo_proofs", nullable = false)
    private int photoProofs;

    /** The number of the claim this one was split from; none for a claim not made by a split. */
    @Column(name = "split_from")
    private String splitFrom;

    /** The user whose open editor locks the claim for everyone else; none while unlocked. */
    @Column(name = "locked_by")
    private String lockedBy;

    /** Whether the claim was added while its period was soft-closed. */
    @Column(name = "late_entry", nullable = false)
    private boolean lateEntry;

    /** The last day (the sheet's today then) a reminder was written for the claim; none when never
     *  reminded. */
    @Column(name = "reminded")
    private LocalDate reminded;

    /** Whether a reminder has escalated the claim; it is escalated once ever. */
    @Column(name = "escalated", nullable = false)
    private boolean escalated;

    /** Whether a steward has waived the claim's late charge; a waived claim bears none from then on. */
    @Column(name = "waived", nullable = false)
    private boolean waived;

    /** Whether the claim is held: a held open claim, unless bound by a statement, counts in no live
     *  total, as a held person's claims do. */
    @Column(name = "held", nullable = false)
    private boolean held;

    /** The people the claim is shared with, in the order they were added. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_share", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "position")
    private List<ClaimShare> shares = new ArrayList<>();

    /** The claim's review day: the sheet's today when it went to REVIEW; none when it never did. */
    @Column(name = "review_on")
    private LocalDate reviewOn;

    /** The claim's external reference, unique on its sheet without regard to case; none when not
     *  given. */
    @Column(name = "ref")
    private String ref;

    /** The claim's tags: lower-case, sorted and comma separated, without repeats; none when it has
     *  none. */
    @Column(name = "tags")
    private String tags;

    /** The reason a VOID claim was voided with, from its sheet's list; none when not given. */
    @Column(name = "void_reason")
    private String voidReason;

    /** The claim a copy in a clone of a sheet was copied from was added as; none for a claim added
     *  on its sheet (added as its own id). */
    @Column(name = "added")
    private Long added;

    protected Claim() {
    }

    /** What the claim was added as, against a forward-only rate's mark: its own id, or for a copy in
     *  a clone the claim it copies was added as; null for one not yet stored or rebuilt from history
     *  without it. */
    public Long getAdded() {
        return added != null ? added : id;
    }

    /** Set what the claim was added as: for a copy in a clone, or a version rebuilt from history. */
    public void setAdded(Long added) {
        this.added = added;
    }

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, LocalDate dueBy, String note) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.date = date;
        this.dueBy = dueBy;
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

    /** Move the claim to another person. */
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

    /** Whether the claim is a correction: its amount is negative. */
    public boolean isCorrection() {
        return amount.signum() < 0;
    }

    public String getCorrects() {
        return corrects;
    }

    public void setCorrects(String corrects) {
        this.corrects = corrects;
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

    public Integer getBatch() {
        return batch;
    }

    public void setBatch(Integer batch) {
        this.batch = batch;
    }

    public boolean isStated() {
        return stated;
    }

    public BigDecimal getStatedDue() {
        return statedDue;
    }

    public LocalDate getDeferredUntil() {
        return deferredUntil;
    }

    public void setDeferredUntil(LocalDate deferredUntil) {
        this.deferredUntil = deferredUntil;
    }

    public String getSplitFrom() {
        return splitFrom;
    }

    public void setSplitFrom(String splitFrom) {
        this.splitFrom = splitFrom;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public boolean isLateEntry() {
        return lateEntry;
    }

    public void setLateEntry(boolean lateEntry) {
        this.lateEntry = lateEntry;
    }

    public LocalDate getReminded() {
        return reminded;
    }

    public void setReminded(LocalDate reminded) {
        this.reminded = reminded;
    }

    public boolean isEscalated() {
        return escalated;
    }

    public void setEscalated(boolean escalated) {
        this.escalated = escalated;
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

    public LocalDate getReviewOn() {
        return reviewOn;
    }

    public void setReviewOn(LocalDate reviewOn) {
        this.reviewOn = reviewOn;
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

    /** A copy of the claim for a person on another sheet, as a transfer carries it: its amount,
     *  dates, terms, note, status, frozen settled figure, opener and editor, deferral, proofs, tags
     *  and the rest of its own values. Its number, batch, reference, statement binding, lock and
     *  listing are left for the other sheet to give; corrects and split-from name this sheet's
     *  numbers, so the caller maps them. */
    public Claim transferredTo(String sheetId, Long personId) {
        Claim copy = new Claim(sheetId, personId, amount, date, dueBy, note);
        copy.terms = terms;
        copy.status = status;
        copy.settledDue = settledDue;
        copy.openedBy = openedBy;
        copy.editedBy = editedBy;
        copy.corrects = corrects;
        copy.deferredUntil = deferredUntil;
        copy.proofs = proofs;
        copy.photoProofs = photoProofs;
        copy.splitFrom = splitFrom;
        copy.lateEntry = lateEntry;
        copy.reminded = reminded;
        copy.escalated = escalated;
        copy.waived = waived;
        copy.held = held;
        copy.reviewOn = reviewOn;
        copy.tags = tags;
        copy.voidReason = voidReason;
        return copy;
    }

    /** A copy of the claim for a clone of the sheet: what a transfer carries, and its reference,
     *  batch and listing too. Its number and statement binding are left for the clone to give, and
     *  it is not locked; corrects and split-from name this sheet's numbers, so the caller maps them. */
    public Claim clonedTo(String sheetId, Long personId) {
        Claim copy = transferredTo(sheetId, personId);
        copy.ref = ref;
        copy.batch = batch;
        copy.listed = listed;
        copy.added = getAdded();
        return copy;
    }

    /** Whether another user than this one holds the claim's editor open. */
    public boolean isLockedFor(String user) {
        return lockedBy != null && !lockedBy.equals(user);
    }

    public int getProofs() {
        return proofs;
    }

    /** Set how many proofs the claim has: only for a version rebuilt from the sheet's history. */
    public void setProofs(int proofs) {
        this.proofs = proofs;
    }

    /** Of the claim's proofs, how many are photos. */
    public int getPhotoProofs() {
        return photoProofs;
    }

    /** Of the claim's proofs, how many are receipts. */
    public int getReceiptProofs() {
        return proofs - photoProofs;
    }

    /** Set how many of the claim's proofs are photos: only for a version rebuilt from the sheet's history. */
    public void setPhotoProofs(int photoProofs) {
        this.photoProofs = photoProofs;
    }

    /** How many of the claim's proofs are of a kind. */
    public int proofsOf(ProofKind kind) {
        return kind == ProofKind.PHOTO ? getPhotoProofs() : getReceiptProofs();
    }

    /** Add a proof of a kind to the claim. */
    public void addProof(ProofKind kind) {
        proofs++;
        if (kind == ProofKind.PHOTO) {
            photoProofs++;
        }
    }

    /** Whether the claim is deferred on a date: OPEN, deferred until a date after it. Until then it
     *  counts in no total, is not overdue, cannot be settled and is left unbound by a statement. */
    public boolean isDeferredOn(LocalDate today) {
        return status == ClaimStatus.OPEN && deferredUntil != null && deferredUntil.isAfter(today);
    }

    /** Whether the claim's due is bound to {@link #statedDue}: it is stated (a PENDING claim too,
     *  like any other claim that counts). */
    public boolean isBound() {
        return stated;
    }

    /** Bind the claim to a due (null: n/a) until the person's next statement. */
    public void bind(BigDecimal due) {
        this.stated = true;
        this.statedDue = due;
    }

    /** Leave the claim unbound, its due live again. */
    public void unbind() {
        this.stated = false;
        this.statedDue = null;
    }

    /** The people the claim is shared with, in the order they were added. */
    public List<ClaimShare> getShares() {
        return List.copyOf(shares);
    }

    /** Share the claim with another person. */
    public void addShare(ClaimShare share) {
        shares.add(share);
    }
}
