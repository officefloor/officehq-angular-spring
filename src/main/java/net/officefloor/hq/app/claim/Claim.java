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

    /** Whether the person's last statement bound this claim to the figure it had then. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The figure bound by the last statement; none while unbound or if it had no rate then. */
    @Column(name = "stated_due")
    private BigDecimal statedDue;

    /**
     * The date the claim is due by: fixed when it is created, the terms-th business day after its
     * date (skipping Saturdays, Sundays and the sheet's holidays), with the terms of its person's
     * grade in force on its date; later changes to the terms, the holidays or its date leave it as it is.
     */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

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

    protected Claim() {
    }

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, LocalDate dueBy, String note,
            String corrects) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.date = date;
        this.dueBy = dueBy;
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
}
