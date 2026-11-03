package net.officefloor.hq.app.claim;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import java.util.function.UnaryOperator;
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

    /** Who last edited the claim; null for a claim never edited. */
    @Column(name = "edited_by")
    private String editedBy;

    /** The number of the bulk settle batch that settled the claim (or left it pending); null when it
     *  was settled one at a time, or never settled. */
    @Column(name = "settle_batch")
    private Integer settleBatch;

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
     *  by the terms of its person's grade in force on that date; later changes to the terms or its
     *  date leave it, but a holiday added on or before it fixes it again (see {@link #refixDueBy}). */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    /** The terms (in business days) the claim's due-by was fixed with; null for a detached revision. */
    @Column(name = "terms")
    private Integer terms;

    /** The day the claim is deferred until (see {@link #isDeferredOn}); null when never deferred. */
    @Column(name = "deferred_until")
    private LocalDate deferredUntil;

    /** How many proofs the claim has; raised by one at a time (see {@link #addProof}). */
    @Column(name = "proofs", nullable = false)
    private int proofs;

    /** The day each proof was given; proofs given before these were kept have none. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_proof", joinColumns = @JoinColumn(name = "claim_id"))
    @Column(name = "added_on")
    private List<LocalDate> proofDays = new ArrayList<>();

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

    /** The claim's comments, in the order they were written (see {@link #comment}). */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @JoinColumn(name = "claim_id", nullable = false, updatable = false)
    @OrderBy("id")
    private List<ClaimComment> comments = new ArrayList<>();

    /** The claim's lines, in the order they were added (see {@link #addLine}). */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @JoinColumn(name = "claim_id", nullable = false, updatable = false)
    @OrderBy("id")
    private List<ClaimLine> lines = new ArrayList<>();

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
     *  It keeps its date and status, but no figure a statement bound it to: it is live
     *  again until its new person's next statement. */
    public void moveTo(Long personId, long listed) {
        this.personId = personId;
        this.listed = listed;
        bind(false, null);
    }

    /** Move the claim to the person its person is merged into, listed at the place given (after every
     *  claim listed so far). It keeps its date, status and any figure a statement bound it to. */
    public void mergedTo(Long personId, long listed) {
        this.personId = personId;
        this.listed = listed;
    }

    /** A copy of this claim for a person on another sheet (it was transferred there): its status,
     *  dates, note, tags, frozen figures, status log, comments and lines kept, its numbers (its own, the
     *  one it corrects and the one it was split off) renumbered as given (null: none), and no
     *  reference, settle batch, editor or figure a statement bound it to. Listed as the newest. */
    public Claim transferredTo(String toSheetId, Long toPersonId, UnaryOperator<String> renumber) {
        Claim copy = new Claim(toSheetId, toPersonId, amount, date, note, renumber.apply(corrects), correctedDate,
                dueBy);
        copy.status = status;
        copy.number = renumber.apply(number);
        copy.settledDue = settledDue;
        copy.openedBy = openedBy;
        copy.editedBy = editedBy;
        copy.terms = terms;
        copy.deferredUntil = deferredUntil;
        copy.proofs = proofs;
        copy.proofDays.addAll(proofDays);
        copy.splitFrom = renumber.apply(splitFrom);
        copy.remindedOn = remindedOn;
        copy.tags = tags;
        copy.voidReason = voidReason;
        copy.lateEntry = lateEntry;
        log.forEach(line -> copy.log.add(new ClaimLog(toSheetId, line.getStatus(), line.getByUser(), line.getOn(),
                line.getDue())));
        comments.forEach(c -> copy.comments.add(new ClaimComment(toSheetId, c.getByUser(), c.getOn(), c.getText())));
        lines.forEach(l -> copy.lines.add(new ClaimLine(toSheetId, l.getLabel(), l.getQty(), l.getUnit())));
        return copy;
    }

    /** A copy of this claim for a person on a clone of its sheet: as {@link #transferredTo}, keeping
     *  its reference too. */
    public Claim clonedTo(String toSheetId, Long toPersonId, UnaryOperator<String> renumber) {
        Claim copy = transferredTo(toSheetId, toPersonId, renumber);
        copy.ref = ref;
        return copy;
    }

    /** The sheet's today when the claim was opened, as its status log records it; null when it was
     *  never opened (or has no log of it). */
    public LocalDate openedOn() {
        return log.stream().filter(line -> line.getStatus() == ClaimStatus.OPEN).map(ClaimLog::getOn)
                .findFirst().orElse(null);
    }

    /** Split the amount off this open claim: it keeps the rest of its amount but no figure a
     *  statement bound it to, and the new open claim returned has the amount, the same date and
     *  note, the number and opener given, the due-by given, this claim's number as split-from, and
     *  this claim's late-entry mark (a split is judged by the original claim alone). */
    public Claim splitOff(BigDecimal part, String number, String openedBy, LocalDate dueBy, int terms) {
        this.amount = amount.subtract(part);
        bind(false, null);
        Claim split = new Claim(sheetId, personId, part, date, note, null, null, dueBy);
        split.terms = terms;
        split.status = ClaimStatus.OPEN;
        split.number = number;
        split.openedBy = openedBy;
        split.editedBy = this.editedBy;
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

    public Integer getTerms() {
        return terms;
    }

    public void setTerms(Integer terms) {
        this.terms = terms;
    }

    /** Fix the claim's due-by again, by the terms it was fixed with (a holiday was added). */
    public void refixDueBy(LocalDate dueBy) {
        this.dueBy = dueBy;
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

    /** How many proofs the claim had at the end of a day: those given after it are left out. */
    public int getProofsOn(LocalDate day) {
        return proofs - (int) proofDays.stream().filter(d -> d.isAfter(day)).count();
    }

    /** Raise the claim's proofs count by one, given on the day. */
    public void addProof(LocalDate on) {
        this.proofs++;
        this.proofDays.add(on);
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

    /** Whether the user opened or last edited the claim, so may not settle or approve it. */
    public boolean openedOrEditedBy(String user) {
        return user.equals(openedBy) || user.equals(editedBy);
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

    public List<ClaimComment> getComments() {
        return List.copyOf(comments);
    }

    /** Add a comment the user wrote on the day (the sheet's today). */
    public void comment(String user, LocalDate on, String text) {
        comments.add(new ClaimComment(sheetId, user, on, text));
    }

    public List<ClaimLine> getLines() {
        return List.copyOf(lines);
    }

    /** Add a line to the claim: its amount becomes the lines' amounts added up. */
    public void addLine(String label, int qty, BigDecimal unit) {
        lines.add(new ClaimLine(sheetId, label, qty, unit));
        amount = lines.stream().map(ClaimLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
    }
}
