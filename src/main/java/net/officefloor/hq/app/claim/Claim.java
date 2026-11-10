package net.officefloor.hq.app.claim;

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
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Generated;

/**
 * A claim by a person on a sheet: an amount (two decimals; below zero only for a correction, which
 * names the number of the settled claim it corrects), a date, a note and a status, once opened its number on the sheet and who opened it, and once settled the due fixed at
 * that moment, and while bound by its person's last statement the figure it had then.
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

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "claim_date", nullable = false)
    private LocalDate date;

    @Column(name = "note", nullable = false)
    private String note;

    /** When the claim was added, in the order claims and forward-only rates are saved (see {@link net.officefloor.hq.app.rate.Rate#appliesTo}). */
    @Generated
    @Column(name = "added_seq", insertable = false, updatable = false)
    private Long addedSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /**
     * The claim's number on its sheet, given when it is opened and kept once VOID; none while DRAFT.
     * Sheet-wide ({@code 1}, {@code 2}, ...), or under numbering per period {@code <period name>-<n>}
     * or {@code X-<n>} in no period.
     */
    @Column(name = "claim_number")
    private String number;

    /** The due fixed when the claim was settled; none while not settled or if its grade had no rate. */
    @Column(name = "settled_due")
    private BigDecimal settledDue;

    /** The user who opened the claim; none while DRAFT, or if voided without being opened. */
    @Column(name = "opened_by")
    private String openedBy;

    /** The user who last edited the claim through its form or its row's amount; none while never edited. */
    @Column(name = "edited_by")
    private String editedBy;

    /** Whether the person's last statement bound this claim to the figure it had then. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The figure bound by the last statement; none while unbound or if it had no rate then. */
    @Column(name = "stated_due")
    private BigDecimal statedDue;

    /**
     * The date the claim is due by: fixed when it is created, the terms-th business day after its
     * date (skipping Saturdays, Sundays and the sheet's holidays), with the terms of its person's
     * grade in force on its date; later changes to the terms or its date leave it as it is. A holiday
     * added on or before it works it out again (see {@link #getTerms}), unless the claim is SETTLED or VOID.
     */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    /** The terms, in business days, the claim's due-by was fixed with. */
    @Column(name = "terms", nullable = false)
    private int terms;

    /** The number of the claim this correction corrects; none for a claim that is not a correction. */
    @Column(name = "corrects")
    private String corrects;

    /** The claim's external reference, unique on its sheet without regard to case; none when not given. */
    @Column(name = "ext_ref")
    private String ref;

    /** The claim's tags: trimmed, lower-cased, without repeats, sorted and comma separated; none when it has none. */
    @Column(name = "tags")
    private String tags;

    /** The number of the claim this claim was split off from; none for a claim not made by a split. */
    @Column(name = "split_from")
    private String splitFrom;

    /** The number of the bulk settle batch that settled the claim (or made it PENDING); none when not settled by one. */
    @Column(name = "batch")
    private Integer batch;

    /** Whether the claim was added while the period its due-by falls in was soft-closed. */
    @Column(name = "late_entry", nullable = false)
    private boolean lateEntry;

    /**
     * The user whose open editor locks the claim for everyone else; none when unlocked. Released
     * when the editor is saved or cancelled, or the sheet's today changes.
     */
    @Column(name = "locked_by")
    private String lockedBy;

    /**
     * The date an OPEN claim is deferred until: while the sheet's today is before it the claim
     * counts in no total, is not overdue and cannot be settled; none when never deferred.
     */
    @Column(name = "deferred_until")
    private LocalDate deferredUntil;

    /** The sheet's today when the claim was last deferred; none when never deferred. */
    @Column(name = "deferred_on")
    private LocalDate deferredOn;

    /** The last sheet-today a reminder was written for the claim; none when never reminded. */
    @Column(name = "reminded_on")
    private LocalDate remindedOn;

    /** Whether the claim has been escalated by a reminder; once ever. */
    @Column(name = "escalated", nullable = false)
    private boolean escalated;

    /** The reason the claim was voided with; none when voided without one, or not voided. */
    @Column(name = "void_reason")
    private String voidReason;

    /** How many proofs the claim has: raised by one at a time, never while SETTLED or VOID. */
    @Column(name = "proofs", nullable = false)
    private int proofs;

    /**
     * The claim's place in the list, newest (highest) first: taken from the store's sequence when
     * the claim is added and again when it is moved to another person.
     */
    @Column(name = "listed", insertable = false, updatable = false)
    private Long listed;

    /** The claim's status log: one entry per status it has had, oldest first. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_log", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "seq")
    @BatchSize(size = 100)
    private List<ClaimLogEntry> log = new ArrayList<>();

    /** The claim's comments, oldest first. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_comment", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "seq")
    @BatchSize(size = 100)
    private List<ClaimComment> comments = new ArrayList<>();

    /** The claim's lines, in the order they were added; while it has any, its amount is their total. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_line", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "seq")
    @BatchSize(size = 100)
    private List<ClaimLine> lines = new ArrayList<>();

    protected Claim() {
    }

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, LocalDate dueBy, int terms,
            String note, String corrects) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.date = date;
        this.dueBy = dueBy;
        this.terms = terms;
        this.note = note;
        this.corrects = corrects;
    }

    public Long getAddedSeq() {
        return addedSeq;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    /**
     * The claim as it arrives on another sheet under a person transferred there: everything it has
     * (status, dates, note, tags, frozen figures, log and comments) but its number, which the caller
     * gives afresh, its reference, which is not carried, and an editor's lock. A figure a statement
     * bound is kept, as the person's statements go with them.
     */
    public Claim transferredTo(String toSheetId, Long toPersonId) {
        Claim copy = new Claim(toSheetId, toPersonId, amount, date, dueBy, terms, note, corrects);
        copy.status = status;
        copy.settledDue = settledDue;
        copy.stated = stated;
        copy.statedDue = statedDue;
        copy.openedBy = openedBy;
        copy.editedBy = editedBy;
        copy.tags = tags;
        copy.splitFrom = splitFrom;
        copy.batch = batch;
        copy.lateEntry = lateEntry;
        copy.deferredUntil = deferredUntil;
        copy.deferredOn = deferredOn;
        copy.remindedOn = remindedOn;
        copy.escalated = escalated;
        copy.voidReason = voidReason;
        copy.proofs = proofs;
        copy.log.addAll(log);
        copy.comments.addAll(comments);
        copy.lines.addAll(lines);
        return copy;
    }

    public Long getPersonId() {
        return personId;
    }

    /** Move the claim to another person. */
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

    public LocalDate getDueBy() {
        return dueBy;
    }

    /** Fix the due-by again, as a holiday added on or before it does. */
    public void setDueBy(LocalDate dueBy) {
        this.dueBy = dueBy;
    }

    public int getTerms() {
        return terms;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getCorrects() {
        return corrects;
    }

    public void setCorrects(String corrects) {
        this.corrects = corrects;
    }

    public String getRef() {
        return ref;
    }

    public void setRef(String ref) {
        this.ref = ref;
    }

    /** The claim's tags, sorted; empty when it has none. */
    public List<String> getTags() {
        return tags == null ? List.of() : List.of(tags.split(","));
    }

    /** Give the claim tags, already trimmed, lower-cased, without repeats and sorted. */
    public void setTags(List<String> tags) {
        this.tags = tags.isEmpty() ? null : String.join(",", tags);
    }

    public Integer getBatch() {
        return batch;
    }

    public void setBatch(Integer batch) {
        this.batch = batch;
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

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    /** Whether another user's open editor locks the claim against the user. */
    public boolean isLockedAgainst(String user) {
        return lockedBy != null && !lockedBy.equals(user);
    }

    /** Whether the claim is a correction: its amount is below zero. Its due has no floor and no late charge. */
    public boolean isCorrection() {
        return amount.signum() < 0;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public void setStatus(ClaimStatus status) {
        this.status = status;
    }

    public String getVoidReason() {
        return voidReason;
    }

    public void setVoidReason(String voidReason) {
        this.voidReason = voidReason;
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

    /** Bind the claim to a figure (stated), or unbind it so its figure is live again. */
    public void bind(boolean stated, BigDecimal statedDue) {
        this.stated = stated;
        this.statedDue = stated ? statedDue : null;
    }

    public LocalDate getDeferredUntil() {
        return deferredUntil;
    }

    /** Defer the claim until a date; {@code madeOn} is the sheet's today. */
    public void setDeferredUntil(LocalDate deferredUntil, LocalDate madeOn) {
        this.deferredUntil = deferredUntil;
        this.deferredOn = madeOn;
    }

    /**
     * Whether the claim was deferred at the end of a day, had it the given status then: deferred
     * while the sheet's today was on or before the day, until a date after it.
     */
    public boolean isDeferredAsOf(ClaimStatus status, LocalDate day) {
        return isDeferredOn(status, day) && (deferredOn == null || !deferredOn.isAfter(day));
    }

    /**
     * The due the claim was settled at, as its status log records it at the end of a day (the last
     * line logged on or before it); none when it had none then.
     */
    public BigDecimal settledDueAt(LocalDate day) {
        String due = null;
        for (ClaimLogEntry entry : log) {
            if (!entry.getDate().isAfter(day)) {
                due = entry.getDue();
            }
        }
        return due == null || due.equals("n/a") ? null : new BigDecimal(due);
    }

    /** Whether the claim is open and deferred on the given day: its deferral date not yet reached. */
    public boolean isDeferredOn(LocalDate today) {
        return isDeferredOn(status, today);
    }

    /** Whether the claim would be deferred on the given day, had it the given status. */
    public boolean isDeferredOn(ClaimStatus status, LocalDate today) {
        return status.isOpen() && deferredUntil != null && today != null && today.isBefore(deferredUntil);
    }

    /**
     * The status the claim had at the end of a day, as its status log records it (the last line
     * logged on or before it); empty when it had none yet.
     */
    public Optional<ClaimStatus> statusAt(LocalDate day) {
        ClaimStatus at = null;
        for (ClaimLogEntry entry : log) {
            if (!entry.getDate().isAfter(day)) {
                at = entry.getStatus();
            }
        }
        return Optional.ofNullable(at);
    }

    public int getProofs() {
        return proofs;
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

    /** Raise the claim's proofs count by one. */
    public void addProof() {
        proofs++;
    }

    public List<ClaimLogEntry> getLog() {
        return List.copyOf(log);
    }

    /** Add a line to the claim's status log. */
    public void log(ClaimLogEntry entry) {
        log.add(entry);
    }

    /** The claim's comments, oldest first. */
    public List<ClaimComment> getComments() {
        return List.copyOf(comments);
    }

    /** Add a comment to the claim. */
    public void comment(ClaimComment comment) {
        comments.add(comment);
    }

    /** The claim's lines, in the order they were added. */
    public List<ClaimLine> getLines() {
        return List.copyOf(lines);
    }

    /** Add a line to the claim: its amount becomes the total of its lines. */
    public void addLine(ClaimLine line) {
        lines.add(line);
        amount = lines.stream().map(ClaimLine::total).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
