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
     *  the sheet's holidays; a holiday added on or before it works it out again from the claim's date,
     *  unless the claim is SETTLED or VOID. */
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

    /** The claim's number, given when it is settled (or approved) and kept once void; none before then
     *  (claims opened before numbering moved to settlement keep the number they were given). It is the
     *  FY<year>-<n> within its fiscal year (or the sheet-wide 1, 2, ... given before), or while period numbering is in force <period name>-<n> or X-<n>. */
    @Column(name = "claim_number")
    private String number;

    /** What was due on the claim when it was settled; none while unsettled, or when no rate applied then. */
    @Column(name = "settled_due", precision = 12, scale = 2)
    private BigDecimal settledDue;

    /** Who opened the claim; none while a draft, or when voided without being opened. */
    @Column(name = "opened_by")
    private String openedBy;

    /** Who last edited the claim through its form or its row's amount entry; none when never edited. */
    @Column(name = "edited_by")
    private String editedBy;

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

    /** Whether a steward set the claim's due-by directly: it then stays as set, a holiday added later
     *  not moving it. */
    @Column(name = "due_by_set", nullable = false)
    private boolean dueBySet;

    /** How many proofs the claim has had added. */
    @Column(name = "proofs", nullable = false)
    private int proofs;

    /** How many of the claim's proofs are receipts. */
    @Column(name = "proofs_receipt", nullable = false)
    private int proofsReceipt;

    /** How many of the claim's proofs are photos. */
    @Column(name = "proofs_photo", nullable = false)
    private int proofsPhoto;

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

    /** Whether the claim was ever escalated; a claim is escalated once ever. */
    @Column(name = "escalated", nullable = false)
    private boolean escalated;

    /** Whether a steward waived the claim's late charge; a waived claim bears none from then on. */
    @Column(name = "waived", nullable = false)
    private boolean waived;

    /** Whether the claim is held; a held claim still counts in every total. */
    @Column(name = "held", nullable = false)
    private boolean held;

    /** The user a steward assigned the claim's review to; none until one is assigned. */
    @Column(name = "review_assignee")
    private String reviewAssignee;

    /** The claim's external reference, unique on its sheet without regard to case; none when it has none. */
    @Column(name = "ref")
    private String ref;

    /** The claim's tags, lower-cased, sorted and comma separated; none when it has none. */
    @Column(name = "tags")
    private String tags;

    /** The reason the claim was voided with; none when it is not void or was voided with none. */
    @Column(name = "void_reason")
    private String voidReason;

    /** The number of the bulk settle batch that last settled the claim; none when it was settled one at
     *  a time, or never settled. */
    @Column(name = "settle_batch")
    private Integer settleBatch;

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

    /** The claim's hold history, oldest first: a line for each hold and each release. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_hold", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "position")
    private List<ClaimHold> holds = new ArrayList<>();

    /** The claim's comments, oldest first. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_comment", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "position")
    private List<ClaimComment> comments = new ArrayList<>();

    /** The claim's shares, in the order they were given. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_share", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "position")
    private List<ClaimShare> shares = new ArrayList<>();

    /** The claim's lines, in the order they were added; while it has any its amount is their total. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_line", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "position")
    private List<ClaimLine> lines = new ArrayList<>();

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

    /** Move the claim to another person as a merge does, as their newest claim at the place given: it keeps
     *  its number, date and any figure a statement bound it to. */
    public void followTo(Long personId, long listed) {
        this.personId = personId;
        this.listed = listed;
    }

    /** A copy of the claim for a person on another sheet, at the place in its list given: its status,
     *  dates, terms, note, tags, frozen figure, proofs, log, comments and lines, with the number given (null:
     *  none) and the numbers it corrects and was split from as given, and any figure a statement bound it
     *  to (with its binding history); no reference, no lock and no settle batch. */
    public Claim copyTo(String otherSheetId, Long otherPersonId, long otherListed, String otherNumber,
            String otherCorrects, String otherSplitFrom) {
        Claim copy = new Claim();
        copy.sheetId = otherSheetId;
        copy.personId = otherPersonId;
        copy.amount = amount;
        copy.date = date;
        copy.dueBy = dueBy;
        copy.dueFrom = dueFrom;
        copy.terms = terms;
        copy.note = note;
        copy.status = status;
        copy.number = otherNumber;
        copy.settledDue = settledDue;
        copy.openedBy = openedBy;
        copy.editedBy = editedBy;
        copy.corrects = otherCorrects;
        copy.listed = otherListed;
        copy.deferredUntil = deferredUntil;
        copy.dueBySet = dueBySet;
        copy.proofs = proofs;
        copy.proofsReceipt = proofsReceipt;
        copy.proofsPhoto = proofsPhoto;
        copy.splitFrom = otherSplitFrom;
        copy.lateEntry = lateEntry;
        copy.remindedOn = remindedOn;
        copy.escalated = escalated;
        copy.waived = waived;
        copy.held = held;
        copy.reviewAssignee = reviewAssignee;
        for (ClaimHold line : holds) copy.holds.add(new ClaimHold(line.getOn(), line.isHeld()));
        copy.tags = tags;
        copy.voidReason = voidReason;
        copy.stated = stated;
        copy.statedDue = statedDue;
        for (ClaimBinding line : bindings) {
            copy.bindings.add(new ClaimBinding(line.getOn(), line.isStated(), line.getStatedDue()));
        }
        for (ClaimLogLine line : log) {
            copy.log.add(new ClaimLogLine(line.getStatus(), line.getBy(), line.getOn(), line.getDue()));
        }
        for (ClaimComment comment : comments) {
            copy.comments.add(new ClaimComment(comment.getBy(), comment.getOn(), comment.getText()));
        }
        for (ClaimLine line : lines) {
            copy.lines.add(new ClaimLine(line.getLabel(), line.getQty(), line.getUnit()));
        }
        return copy;
    }

    /** The sheet's today when the claim was first opened, as its log records it; null when it never was. */
    public LocalDate openedOn() {
        for (ClaimLogLine line : log) if (line.getStatus() == ClaimStatus.OPEN) return line.getOn();
        return null;
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

    /** Set the due-by directly; it stays as set from then on. */
    public void setDueBy(LocalDate dueBy) {
        this.dueBy = dueBy;
        this.dueBySet = true;
    }

    public boolean isDueBySet() {
        return dueBySet;
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

    /** The reason the claim was voided with, or null. */
    public String getVoidReason() {
        return voidReason;
    }

    public void setVoidReason(String voidReason) {
        this.voidReason = voidReason;
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

    /** The day the claim was last deleted, as its log records it; null when it never was. */
    public LocalDate deletedOn() {
        LocalDate on = null;
        for (ClaimLogLine line : log) if (line.getStatus() == ClaimStatus.DELETED) on = line.getOn();
        return on;
    }

    /** Log the claim's status now: who gave it, the sheet's today and its due (null: n/a) then. */
    public void logStatus(String by, LocalDate on, BigDecimal due) {
        log.add(new ClaimLogLine(status, by, on, due));
    }

    public List<ClaimComment> getComments() {
        return List.copyOf(comments);
    }

    /** Add a comment written by the user while the sheet's today is the date given. */
    public void comment(String by, LocalDate on, String text) {
        comments.add(new ClaimComment(by, on, text));
    }

    public List<ClaimLine> getLines() {
        return List.copyOf(lines);
    }

    /** Add a line; the claim's amount becomes the total of its lines. */
    public void addLine(String label, int qty, BigDecimal unit) {
        lines.add(new ClaimLine(label, qty, unit));
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (ClaimLine line : lines) total = total.add(line.total());
        this.amount = total;
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

    public Integer getSettleBatch() {
        return settleBatch;
    }

    public void setSettleBatch(Integer settleBatch) {
        this.settleBatch = settleBatch;
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

    /** Whether the claim is bound to its stated figure now: stated (a PENDING claim included, like any
     *  other claim that counts). */
    public boolean isBound() {
        return stated;
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

    /** How many of the claim's proofs are of the kind. */
    public int getProofs(ProofKind kind) {
        return kind == ProofKind.PHOTO ? proofsPhoto : proofsReceipt;
    }

    /** Raise the claim's proofs count, and its count of the kind, by one. */
    public void addProof(ProofKind kind) {
        proofs++;
        if (kind == ProofKind.PHOTO) proofsPhoto++;
        else proofsReceipt++;
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

    public boolean isEscalated() {
        return escalated;
    }

    /** Record the claim escalated. */
    public void escalate() {
        this.escalated = true;
    }

    public boolean isWaived() {
        return waived;
    }

    /** Record the claim's late charge waived. */
    public void waive() {
        this.waived = true;
    }

    public boolean isHeld() {
        return held;
    }

    public List<ClaimShare> getShares() {
        return List.copyOf(shares);
    }

    /** Give the person a share of the claim, as a percent of its due. */
    public void share(Long personId, BigDecimal share) {
        shares.add(new ClaimShare(personId, share));
    }

    /** The claim's shares added up, as a percent of its due. */
    public BigDecimal sharedPct() {
        return shares.stream().map(ClaimShare::getShare).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public String getReviewAssignee() {
        return reviewAssignee;
    }

    public void setReviewAssignee(String reviewAssignee) {
        this.reviewAssignee = reviewAssignee;
    }

    /** Hold the claim, or release it, while the sheet's today is the date given. */
    public void setHeld(boolean held, LocalDate today) {
        this.held = held;
        holds.add(new ClaimHold(today, held));
    }

    /** Whether the claim was held at the end of the date given, as its hold history records it. */
    public boolean heldAsOf(LocalDate date) {
        boolean then = false;
        for (ClaimHold line : holds) if (line.madeBy(date)) then = line.isHeld();
        return then;
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
