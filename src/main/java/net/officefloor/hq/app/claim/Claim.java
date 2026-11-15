package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
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
     *  terms, else the terms of their grade in force on that date, else the sheet's then; later
     *  changes to the terms or its date leave it. */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    @Column(name = "note", nullable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /** The sheet's number for the claim, given when it is opened and kept if it is voided; none
     *  while DRAFT. A sheet-wide number (1, 2, ...), or under period numbering one within its
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

    /** The claim's external reference, unique on its sheet without regard to case; none when not
     *  given. */
    @Column(name = "ref")
    private String ref;

    /** The claim's tags: lower-case, sorted and comma separated, without repeats; none when it has
     *  none. */
    @Column(name = "tags")
    private String tags;

    protected Claim() {
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

    /** Add a proof to the claim. */
    public void addProof() {
        proofs++;
    }

    /** Whether the claim is deferred on a date: OPEN, deferred until a date after it. Until then it
     *  counts in no total, is not overdue, cannot be settled and is left unbound by a statement. */
    public boolean isDeferredOn(LocalDate today) {
        return status == ClaimStatus.OPEN && deferredUntil != null && deferredUntil.isAfter(today);
    }

    /** Whether the claim's due is bound to {@link #statedDue}: it is stated and not PENDING (a
     *  PENDING claim shows its live figure, and is bound again if rejected back to OPEN). */
    public boolean isBound() {
        return stated && status != ClaimStatus.PENDING;
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
}
