package net.officefloor.hq.app.claim;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.Generated;

/** A claim a person makes: an amount (two decimals), a date, a note and a status. A claim with a
 *  negative amount is a correction of a settled claim of the same person, named by its number. */
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

    /** The claim's number on its sheet, given when it is opened and kept when voided; null for a
     *  claim never opened. */
    @Column(name = "number")
    private String number;

    /** The due fixed when the claim was settled; null when not settled, or settled with no rate. */
    @Column(name = "settled_due", precision = 14, scale = 2)
    private BigDecimal settledDue;

    /** Who opened the claim; null for a claim never opened. */
    @Column(name = "opened_by")
    private String openedBy;

    /** Whether the person's last statement bound this claim to the due it had then. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The due the last statement bound the claim to; null when unbound, or bound with no rate
     *  (its due then stays live). */
    @Column(name = "stated_due", precision = 14, scale = 2)
    private BigDecimal statedDue;

    /** Whether the last statement bound the claim to n/a: it had no due then because the rate that
     *  would apply to it had ended (its due then does not stay live). */
    @Column(name = "stated_na", nullable = false)
    private boolean statedNa;

    /** The number of the settled claim of the same person this correction corrects; null for a claim
     *  that is not a correction. */
    @Column(name = "corrects")
    private String corrects;

    /** The date of the claim this correction corrects, which it rounds by; null for a claim that is
     *  not a correction. */
    @Column(name = "corrected_date")
    private LocalDate correctedDate;

    /** The day the claim is due by: fixed when it is made, the terms-th business day after its date
     *  by the terms of its person's grade in force on that date; later changes to the terms, the
     *  holidays or its date leave it. */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    /** The day the claim is deferred until (see {@link #isDeferredOn}); null when never deferred. */
    @Column(name = "deferred_until")
    private LocalDate deferredUntil;

    /** How many proofs the claim has; raised by one at a time (see {@link #addProof}). */
    @Column(name = "proofs", nullable = false)
    private int proofs;

    /** The number of the claim this claim was split off (see {@link #splitOff}); null for a claim
     *  that was not split off one. */
    @Column(name = "split_from")
    private String splitFrom;

    /** The user who has the claim's editor open, locking it for every other user; null when no one
     *  has. */
    @Column(name = "edit_locked_by")
    private String editLockedBy;

    /** The last day (the sheet's today then) a reminder was written for the claim; null when never. */
    @Column(name = "reminded_on")
    private LocalDate remindedOn;

    /** The claim's external reference; null when it has none. No two claims of a sheet share one,
     *  comparing without regard to case. */
    @Column(name = "ref")
    private String ref;

    /** The claim's tags, sorted and comma separated (see {@link Tags}); null when it has none. */
    @Column(name = "tags")
    private String tags;

    /** The reason from the sheet's list the claim was voided with; null when none was given. */
    @Column(name = "void_reason")
    private String voidReason;

    /** Whether the claim was added while the period its due-by falls in was soft-closed. */
    @Column(name = "late_entry", nullable = false)
    private boolean lateEntry;

    /** Where the claim is listed: newest first by this. A new claim takes the next from the
     *  database, and a moved claim takes the next again (see {@link #moveTo}). */
    @Generated
    @Column(name = "listed", insertable = false)
    private Long listed;

    /** The claim's status log, oldest first: a line for each status it has had (see {@link #logStatus}). */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @JoinColumn(name = "claim_id", nullable = false, updatable = false)
    @OrderBy("id")
    private List<ClaimLog> log = new ArrayList<>();

    protected Claim() {
    }

    /** The claim as a revision of it has it, detached: for working out figures only, never saved. */
    static Claim revised(ClaimRevision revision) {
        Claim claim = new Claim(revision.getSheetId(), revision.getPersonId(), revision.getAmount(),
                revision.getDate(), "", null, revision.getCorrectedDate(), revision.getDueBy());
        claim.id = revision.getClaimId();
        claim.status = revision.getStatus();
        claim.settledDue = revision.getSettledDue();
        claim.bind(revision.isStated(), revision.getStatedDue(), revision.isStatedNa());
        claim.deferredUntil = revision.getDeferredUntil();
        return claim;
    }

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, String note, String corrects,
            LocalDate correctedDate, LocalDate dueBy) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.date = date;
        this.note = note;
        this.corrects = corrects;
        this.correctedDate = correctedDate;
        this.dueBy = dueBy;
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

    public Long getListed() {
        return listed;
    }

    /** Move the claim to another person, listed at the place given (after every claim listed so far).
     *  It keeps its number, date and status, but no figure a statement bound it to: it is live
     *  again until its new person's next statement. */
    public void moveTo(Long personId, long listed) {
        this.personId = personId;
        this.listed = listed;
        bind(false, null);
    }

    /** Split the amount off this open claim: it keeps the rest of its amount but no figure a
     *  statement bound it to, and the new open claim returned has the amount, the same date and
     *  note, the number and opener given, the due-by given, this claim's number as split-from, and
     *  this claim's late-entry mark (a split is judged by the original claim alone). */
    public Claim splitOff(BigDecimal part, String number, String openedBy, LocalDate dueBy) {
        this.amount = amount.subtract(part);
        bind(false, null);
        Claim split = new Claim(sheetId, personId, part, date, note, null, null, dueBy);
        split.status = ClaimStatus.OPEN;
        split.number = number;
        split.openedBy = openedBy;
        split.splitFrom = this.number;
        split.lateEntry = this.lateEntry;
        split.tags = this.tags;
        return split;
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

    public String getRef() {
        return ref;
    }

    public void setRef(String ref) {
        this.ref = ref;
    }

    public String getSplitFrom() {
        return splitFrom;
    }

    public boolean isLateEntry() {
        return lateEntry;
    }

    public void markLateEntry() {
        this.lateEntry = true;
    }

    public String getEditLockedBy() {
        return editLockedBy;
    }

    public void setEditLockedBy(String editLockedBy) {
        this.editLockedBy = editLockedBy;
    }

    /** Whether another user than this one has the claim's editor open. */
    public boolean editLockedFor(String user) {
        return editLockedBy != null && !editLockedBy.equals(user);
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

    public LocalDate getCorrectedDate() {
        return correctedDate;
    }

    public void setCorrectedDate(LocalDate correctedDate) {
        this.correctedDate = correctedDate;
    }

    /** The date the claim's figures round by: the date of the claim a correction corrects, or else
     *  its own. */
    public LocalDate roundsBy() {
        return correctedDate != null ? correctedDate : date;
    }

    /** Whether the claim is a correction: its amount is below 0.00. */
    public boolean isCorrection() {
        return amount.signum() < 0;
    }

    public LocalDate getDueBy() {
        return dueBy;
    }

    public LocalDate getDeferredUntil() {
        return deferredUntil;
    }

    public void deferUntil(LocalDate until) {
        this.deferredUntil = until;
    }

    public LocalDate getRemindedOn() {
        return remindedOn;
    }

    public void remindOn(LocalDate day) {
        this.remindedOn = day;
    }

    public int getProofs() {
        return proofs;
    }

    /** Raise the claim's proofs count by one. */
    public void addProof() {
        this.proofs++;
    }

    /** Whether the claim is deferred on the day: it is open and the day is before the one it is
     *  deferred until. A deferred claim counts in no total, is not overdue, cannot be settled and a
     *  statement leaves it unbound; from that day on it is an open claim like any other. */
    public boolean isDeferredOn(LocalDate day) {
        return status == ClaimStatus.OPEN && deferredUntil != null && day.isBefore(deferredUntil);
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

    public boolean isStatedNa() {
        return statedNa;
    }

    /** Bind the claim to a due (null: none), or unbind it. */
    public void bind(boolean stated, BigDecimal statedDue) {
        bind(stated, statedDue, false);
    }

    /** Bind the claim to a due (null: none, or n/a when {@code na}), or unbind it. */
    public void bind(boolean stated, BigDecimal statedDue, boolean na) {
        this.stated = stated;
        this.statedDue = stated ? statedDue : null;
        this.statedNa = stated && statedDue == null && na;
    }

    /** The status the claim had at the end of the day, as its status log records it: that of the
     *  last line logged on or before the day; null when it was not yet made then. A claim with no
     *  log has its status now. */
    public ClaimStatus statusOn(LocalDate day) {
        if (log.isEmpty()) {
            return status;
        }
        ClaimStatus then = null;
        for (ClaimLog line : log) {
            if (!line.getOn().isAfter(day)) {
                then = line.getStatus();
            }
        }
        return then;
    }

    public List<ClaimLog> getLog() {
        return List.copyOf(log);
    }

    /** Log the status the claim has now: changed by the user on the day (the sheet's today), with
     *  its due as computed at that moment (null when it has none). */
    public void logStatus(String user, LocalDate on, BigDecimal due) {
        log.add(new ClaimLog(sheetId, status, user, on, due));
    }
}
