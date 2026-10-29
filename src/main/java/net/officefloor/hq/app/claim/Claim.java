package net.officefloor.hq.app.claim;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

    /** The claim's external reference, unique on its sheet without regard to case; null for a claim with none. */
    @Column(name = "ref")
    private String ref;

    /** The reason the claim was voided with; null for one not voided, or voided without one. */
    @Column(name = "void_reason")
    private String voidReason;

    /** The claim's tags, sorted and comma separated; null for a claim with none. */
    @Column(name = "tags")
    private String tags;

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

    /** The user who last edited the claim; null for a claim never edited. */
    @Column(name = "edited_by")
    private String editedBy;

    /** The number of the bulk settle batch that settled the claim (or made it PENDING); null for none. */
    @Column(name = "settle_batch")
    private Integer settleBatch;

    /** The user whose open editor holds the claim, so no one else may edit it; null while none does. */
    @Column(name = "locked_by")
    private String lockedBy;

    /** Whether the claim is bound by its person's last statement. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The figure the claim is bound to by its person's last statement; null for n/a or when not stated. */
    @Column(name = "stated_due", precision = 14, scale = 2)
    private BigDecimal statedDue;

    /** The number of the settled claim a correction corrects; null for a claim that is not a correction. */
    @Column(name = "corrects")
    private String corrects;

    /** The number of the claim this one was split off; null for a claim not split off another. */
    @Column(name = "split_from")
    private String splitFrom;

    /** The date the claim's rounding follows: its own date, or for a correction the date of the claim it corrects. */
    @Column(name = "rounds_on", nullable = false)
    private LocalDate roundsOn;

    /**
     * The date the claim is due by: fixed when it is added, from the terms in force for its person's grade on its
     * date; later changes to the terms or to its date leave it as it is. A holiday added on or before it re-fixes it
     * from its date with the terms it was fixed with, unless the claim is SETTLED or VOID.
     */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    /** The terms (business days) the claim's due-by was fixed with. */
    @Column(name = "terms", nullable = false)
    private int terms;

    /**
     * The date the claim is deferred until; null for one never deferred. While the sheet's today is before it an
     * OPEN claim is deferred (see {@link #isDeferredOn}).
     */
    @Column(name = "deferred_until")
    private LocalDate deferredUntil;

    /** The sheet's today when the claim was deferred; null for one never deferred, or before that was kept. */
    @Column(name = "deferred_on")
    private LocalDate deferredOn;

    /** The last sheet's today a reminder was written for the claim; null for one never reminded. */
    @Column(name = "reminded_on")
    private LocalDate remindedOn;

    /** Whether the claim was added while its period was soft-closed. */
    @Column(name = "late_entry", nullable = false)
    private boolean lateEntry;

    /** How many proofs the claim has; raised by one at a time. */
    @Column(name = "proofs", nullable = false)
    private int proofs;

    /**
     * The claim's place in the list, newest first: the next place when it is added or moved to another person. The
     * store gives it; a move takes the next through {@link ClaimRepository#relist}.
     */
    @Column(name = "listed", insertable = false, updatable = false)
    private Long listed;

    /** The claim's status log, oldest first: a line for each status it has had, from its DRAFT on. */
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @JoinColumn(name = "claim_id", nullable = false)
    @OrderBy("id ASC")
    private List<ClaimLogEntry> log = new ArrayList<>();

    /** The claim's comments, in the order they were written. */
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @JoinColumn(name = "claim_id", nullable = false)
    @OrderBy("id ASC")
    private List<ClaimComment> comments = new ArrayList<>();

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

    public LocalDate getRemindedOn() {
        return remindedOn;
    }

    public void setRemindedOn(LocalDate remindedOn) {
        this.remindedOn = remindedOn;
    }

    public LocalDate getDeferredUntil() {
        return deferredUntil;
    }

    /** Defers the claim until the date, while the sheet's today is the made-on date. */
    public void setDeferredUntil(LocalDate deferredUntil, LocalDate madeOn) {
        this.deferredUntil = deferredUntil;
        this.deferredOn = madeOn;
    }

    /**
     * Whether the claim was deferred at the end of the day: OPEN then, deferred while the sheet's today was on or
     * before the day, until a date after it.
     */
    public boolean isDeferredAsOf(LocalDate day) {
        return statusAtEndOf(day) == ClaimStatus.OPEN && deferredUntil != null
                && (deferredOn == null || !deferredOn.isAfter(day)) && day.isBefore(deferredUntil);
    }

    /**
     * Whether the claim is deferred on the date: OPEN and deferred until a date after it. A deferred claim counts in
     * no total, is not overdue, cannot be settled and is left unbound by a statement; from its date on it is an OPEN
     * claim like any other.
     */
    public boolean isDeferredOn(LocalDate today) {
        return status == ClaimStatus.OPEN && deferredUntil != null && today != null && today.isBefore(deferredUntil);
    }

    public boolean isLateEntry() {
        return lateEntry;
    }

    public void setLateEntry(boolean lateEntry) {
        this.lateEntry = lateEntry;
    }

    public int getProofs() {
        return proofs;
    }

    /** Raises the claim's proofs count by one. */
    public void addProof() {
        proofs++;
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

    public String getSplitFrom() {
        return splitFrom;
    }

    public void setSplitFrom(String splitFrom) {
        this.splitFrom = splitFrom;
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

    /** Moves the claim to the other sheet, still the person's; what holds only on its old sheet is left behind there. */
    public void moveToSheet(String sheetId) {
        this.sheetId = sheetId;
        this.ref = null;
        this.settleBatch = null;
        this.lockedBy = null;
    }

    /** The sheet's today when the claim was first opened, as its status log records it; null when it does not. */
    public LocalDate openedOn() {
        return log.stream().filter(e -> e.getStatus() == ClaimStatus.OPEN).map(ClaimLogEntry::getOn).findFirst()
                .orElse(null);
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

    public String getRef() {
        return ref;
    }

    public void setRef(String ref) {
        this.ref = ref;
    }

    public String getVoidReason() {
        return voidReason;
    }

    public void setVoidReason(String voidReason) {
        this.voidReason = voidReason;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
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

    /** Takes the claim's number away, so it can be numbered afresh. */
    public void clearNumber() {
        this.number = null;
        this.numberSequence = null;
        this.numberInSequence = null;
    }

    public String getNumberSequence() {
        return numberSequence;
    }

    public Long getNumberInSequence() {
        return numberInSequence;
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

    public String getEditedBy() {
        return editedBy;
    }

    public void setEditedBy(String editedBy) {
        this.editedBy = editedBy;
    }

    public Integer getSettleBatch() {
        return settleBatch;
    }

    public void setSettleBatch(Integer settleBatch) {
        this.settleBatch = settleBatch;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
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

    public List<ClaimLogEntry> getLog() {
        return log;
    }

    public List<ClaimComment> getComments() {
        return comments;
    }

    /** Adds a comment: by the user, on the sheet's today. */
    public void comment(String by, LocalDate on, String text) {
        comments.add(new ClaimComment(by, on, text));
    }

    /**
     * The status the claim had at the end of the day, as its log records it: the last line logged on or before it (a
     * line with no date counts as before any day); null when the claim was added after it. A claim with no log has
     * its status now.
     */
    public ClaimStatus statusAtEndOf(LocalDate day) {
        if (log.isEmpty()) {
            return status;
        }
        ClaimStatus then = null;
        for (ClaimLogEntry e : log) {
            if (e.getOn() == null || !e.getOn().isAfter(day)) {
                then = e.getStatus();
            }
        }
        return then;
    }

    /**
     * The due its status log records at the end of the day: that of the last line logged on or before it; null when
     * there is none.
     */
    public BigDecimal dueLoggedAtEndOf(LocalDate day) {
        BigDecimal then = null;
        for (ClaimLogEntry e : log) {
            if (e.getOn() == null || !e.getOn().isAfter(day)) {
                then = e.getDue();
            }
        }
        return then;
    }

    /** The date its status log last records it SETTLED; null when it never was, or the line has no date. */
    public LocalDate settledOn() {
        LocalDate on = null;
        for (ClaimLogEntry e : log) {
            if (e.getStatus() == ClaimStatus.SETTLED) {
                on = e.getOn();
            }
        }
        return on;
    }

    /** The sheet's today its status log records it last deleted on; null for one never deleted. */
    public LocalDate deletedOn() {
        LocalDate on = null;
        for (ClaimLogEntry e : log) {
            if (e.getStatus() == ClaimStatus.DELETED) {
                on = e.getOn();
            }
        }
        return on;
    }

    /** Logs the status the claim now has: by the user, on the sheet's today, at the due computed now. */
    public void logStatus(String by, LocalDate on, BigDecimal due) {
        log.add(new ClaimLogEntry(status, by, on, due));
    }
}
