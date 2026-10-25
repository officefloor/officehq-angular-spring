package net.officefloor.hq.app.claim;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
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

    /** The date the claim is due by: the {@link #terms}-th business day after {@link #dueFrom}, past
     *  the sheet's holidays now, worked out again whenever they change. */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    /** The date the claim's due-by counts from: its date when it was added; a later change to the
     *  claim's date leaves it as it is. */
    @Column(name = "due_from", nullable = false)
    private LocalDate dueFrom;

    /** The terms, in business days, the claim was fixed with when it was added, from those of its
     *  person (else their grade's, else the sheet's) in force on its date; later changes to the terms
     *  leave them as they are. */
    @Column(name = "terms", nullable = false)
    private int terms;

    @Column(name = "note", nullable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /** The claim's number, given when it is opened and kept once void; none while a draft. It is the
     *  sheet-wide 1, 2, ..., or while period numbering is in force <period name>-<n> or X-<n>. */
    @Column(name = "claim_number")
    private String number;

    /** What was due on the claim when it was settled; none while unsettled, or when no rate applied then. */
    @Column(name = "settled_due", precision = 12, scale = 2)
    private BigDecimal settledDue;

    /** Who opened the claim; none while a draft, or when voided without being opened. */
    @Column(name = "opened_by")
    private String openedBy;

    /** Whether the person's last statement bound the claim to a figure; it then shows stated due. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The figure the last statement bound the claim to; none when unbound, or when it was n/a then. */
    @Column(name = "stated_due", precision = 12, scale = 2)
    private BigDecimal statedDue;

    /** The number of the settled claim of the same person a correction (a negative amount) corrects; none otherwise. */
    @Column(name = "corrects")
    private String corrects;

    /** The claim's place in the sheet's list, which is newest first: the sheet's next place when it is
     *  added, and again when it is moved to another person. */
    @Column(name = "listed", nullable = false)
    private long listed;

    /** The date the claim is deferred until: while the sheet's today is before it an OPEN claim counts
     *  in no total, is not overdue, cannot be settled and no statement binds it; none when never deferred. */
    @Column(name = "deferred_until")
    private LocalDate deferredUntil;

    /** How many proofs the claim has had added. */
    @Column(name = "proofs", nullable = false)
    private int proofs;

    /** The number of the claim this one was split off from; none when it was not split off. */
    @Column(name = "split_from")
    private String splitFrom;

    /** The user whose open editor locks the claim for other users; none when it is not locked. */
    @Column(name = "locked_by")
    private String lockedBy;

    /** Whether the claim was added while the period its due-by falls in was soft-closed. */
    @Column(name = "late_entry", nullable = false)
    private boolean lateEntry;

    /** The last day (the sheet's today then) a reminder was written for the claim; none when never reminded. */
    @Column(name = "reminded_on")
    private LocalDate remindedOn;

    /** The claim's external reference, unique on its sheet without regard to case; none when it has none. */
    @Column(name = "ref")
    private String ref;

    /** The claim's tags, lower-cased, sorted and comma separated; none when it has none. */
    @Column(name = "tags")
    private String tags;

    /** The claim's status log, oldest first: a line for each status it has had. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_log", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "position")
    private List<ClaimLogLine> log = new ArrayList<>();

    /** The claim's binding history, oldest first: a line for each binding to a figure and each loss of one. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_binding", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "position")
    private List<ClaimBinding> bindings = new ArrayList<>();

    protected Claim() {
    }

    /** A claim whose due-by, the date given, is fixed from its date with the terms given. */
    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, String note, String corrects,
            int terms, LocalDate dueBy) {
        this.sheetId = sheetId;
        this.personId = personId;
        set(amount, date, note, corrects);
        this.dueFrom = date;
        this.terms = terms;
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

    public long getListed() {
        return listed;
    }

    public void setListed(long listed) {
        this.listed = listed;
    }

    /** Move the claim to another person, as their newest claim at the place given, while the sheet's today
     *  is the date given: it keeps its number and date, and loses any figure a statement bound it to, its
     *  due live again. */
    public void moveTo(Long personId, long listed, LocalDate today) {
        this.personId = personId;
        this.listed = listed;
        unbind(today);
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getDate() {
        return date;
    }

    public LocalDate getDueBy() {
        return dueBy;
    }

    public LocalDate getDueFrom() {
        return dueFrom;
    }

    public int getTerms() {
        return terms;
    }

    /** Set the due-by worked out again from the claim's due-from and terms; whether it moved. */
    public boolean moveDueBy(LocalDate dueBy) {
        if (dueBy.equals(this.dueBy)) return false;
        this.dueBy = dueBy;
        return true;
    }

    public String getNote() {
        return note;
    }

    public String getRef() {
        return ref;
    }

    /** Give the claim its external reference, or (null) none. */
    public void setRef(String ref) {
        this.ref = ref;
    }

    /** The claim's tags, sorted; empty when it has none. */
    public List<String> getTags() {
        return tags == null ? List.of() : List.of(tags.split(","));
    }

    /** Give the claim its tags, each lower-cased and sorted, or (empty) none. */
    public void setTags(List<String> tags) {
        this.tags = tags.isEmpty() ? null : String.join(",", tags);
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public void setStatus(ClaimStatus status) {
        this.status = status;
    }

    public List<ClaimLogLine> getLog() {
        return List.copyOf(log);
    }

    /** The status the claim had at the end of the day given, as its log records it; null when it was
     *  not yet added then. */
    public ClaimStatus statusAsOf(LocalDate date) {
        ClaimStatus then = null;
        for (ClaimLogLine line : log) if (!line.getOn().isAfter(date)) then = line.getStatus();
        return then;
    }

    /** The due fixed on the claim at the end of the day given, as its log records it: the due of its last
     *  line by then when that is SETTLED; null when it was not settled then, or its due was n/a. */
    public BigDecimal settledDueAsOf(LocalDate date) {
        ClaimLogLine then = null;
        for (ClaimLogLine line : log) if (!line.getOn().isAfter(date)) then = line;
        return then != null && then.getStatus() == ClaimStatus.SETTLED ? then.getDue() : null;
    }

    /** The claim's binding at the end of the day given: its last binding line made by then, when that bound
     *  it to a figure; null when it was unbound then. */
    public ClaimBinding bindingAsOf(LocalDate date) {
        ClaimBinding then = null;
        for (ClaimBinding line : bindings) if (line.madeBy(date)) then = line;
        return then != null && then.isStated() ? then : null;
    }

    /** The day the claim was last settled, as its log records it; null when it never was. */
    public LocalDate settledOn() {
        LocalDate on = null;
        for (ClaimLogLine line : log) if (line.getStatus() == ClaimStatus.SETTLED) on = line.getOn();
        return on;
    }

    /** Log the claim's status now: who gave it, the sheet's today and its due (null: n/a) then. */
    public void logStatus(String by, LocalDate on, BigDecimal due) {
        log.add(new ClaimLogLine(status, by, on, due));
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

    public String getCorrects() {
        return corrects;
    }

    /** Whether the claim is a correction: its amount is negative. */
    public boolean isCorrection() {
        return amount.signum() < 0;
    }

    public boolean isStated() {
        return stated;
    }

    /** Whether the claim is bound to its stated figure now: stated, and not PENDING, as no statement
     *  binds a PENDING claim (its binding holds again should it be rejected). */
    public boolean isBound() {
        return stated && status != ClaimStatus.PENDING;
    }

    public LocalDate getDeferredUntil() {
        return deferredUntil;
    }

    /** Defer the claim until the date. */
    public void deferUntil(LocalDate until) {
        this.deferredUntil = until;
    }

    /** Whether the claim is deferred on the sheet's today: OPEN, with today before its deferred-until date. */
    public boolean isDeferredOn(LocalDate today) {
        return deferredUntil != null && status == ClaimStatus.OPEN && today != null && today.isBefore(deferredUntil);
    }

    public int getProofs() {
        return proofs;
    }

    /** Raise the claim's proofs count by one. */
    public void addProof() {
        proofs++;
    }

    public String getSplitFrom() {
        return splitFrom;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public boolean isLateEntry() {
        return lateEntry;
    }

    /** Mark the claim as added while its period was soft-closed. */
    public void markLateEntry() {
        this.lateEntry = true;
    }

    public LocalDate getRemindedOn() {
        return remindedOn;
    }

    /** Record a reminder written for the claim on the sheet's today given. */
    public void remind(LocalDate today) {
        this.remindedOn = today;
    }

    /** Whether the claim is locked by a user other than this one. */
    public boolean isLockedAgainst(String user) {
        return lockedBy != null && !lockedBy.equals(user);
    }

    /** Lock the claim for the user, or (null) release it. */
    public void lockFor(String user) {
        this.lockedBy = user;
    }

    /** Split the amount given off the claim into a new OPEN claim of the same person, date and note,
     *  with the number given, opened by the user and due by the date given (fixed from its date with the
     *  terms given), split from
     *  this claim's number. This claim keeps the rest of its amount and loses any figure a statement
     *  bound it to. */
    public Claim splitOff(BigDecimal part, String number, String user, int terms, LocalDate dueBy,
            LocalDate today) {
        Claim split = new Claim(sheetId, personId, part, date, note, null, terms, dueBy);
        split.status = ClaimStatus.OPEN;
        split.number = number;
        split.openedBy = user;
        split.splitFrom = this.number;
        split.tags = this.tags;
        this.amount = amount.subtract(part).setScale(2);
        unbind(today);
        return split;
    }

    /** Re-date the claim: its due-by, number and any figure a statement bound it to are kept. */
    public void redate(LocalDate date) {
        this.date = date;
    }

    public BigDecimal getStatedDue() {
        return statedDue;
    }

    /** Bind the claim to a figure (null: n/a) as a statement takes it while the sheet's today is the date given. */
    public void bind(BigDecimal figure, LocalDate today) {
        this.stated = true;
        this.statedDue = figure;
        bindings.add(new ClaimBinding(today, true, figure));
    }

    /** Leave the claim unbound, its due live again, while the sheet's today is the date given. */
    public void unbind(LocalDate today) {
        if (stated) bindings.add(new ClaimBinding(today, false, null));
        this.stated = false;
        this.statedDue = null;
    }

    /** Change the claim's amount, date, note and the number it corrects; the amount is kept to two
     *  decimals, and only a correction keeps the number it corrects. */
    public void set(BigDecimal amount, LocalDate date, String note, String corrects) {
        this.amount = amount.setScale(2);
        this.date = date;
        this.note = note;
        this.corrects = isCorrection() ? corrects : null;
    }
}
