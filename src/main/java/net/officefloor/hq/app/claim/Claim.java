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

/**
 * A claim a person makes: an amount (two decimals), a date, a note and a status. A negative amount is a
 * correction, naming the number of the SETTLED claim of the same person it corrects.
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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /** The number the claim got when opened; null while a draft; a voided claim keeps it. */
    @Column(name = "claim_number")
    private Integer number;

    /** The fiscal year (FY<year>) a sheet-wide number counts in, shown <FY>-<n>; null for a number given before. */
    @Column(name = "fiscal_year")
    private String fiscalYear;

    /** The sequence a period-numbered claim counts in ('P<period id>', or 'X' for no period); null otherwise. */
    @Column(name = "period_sequence")
    private String periodSequence;

    /** The claim's place in its period sequence; null for a sheet-wide number. */
    @Column(name = "period_seq_number")
    private Integer periodSeqNumber;

    /** The number as shown for a period-numbered claim (<period name>-<n>, or X-<n>); null otherwise. */
    @Column(name = "period_number")
    private String periodNumber;

    /** The user who opened the claim; null while a draft and once voided. */
    @Column(name = "opened_by")
    private String openedBy;

    /** The bulk settle batch that settled the claim (to SETTLED or PENDING); null when settled one at a time or never. */
    @Column(name = "settle_batch")
    private Integer settleBatch;

    /** The user who last edited the claim; null while never edited. */
    @Column(name = "edited_by")
    private String editedBy;

    /** The due fixed when the claim was settled; null while not settled or when there was no rate. */
    @Column(name = "settled_due", precision = 14, scale = 2)
    private BigDecimal settledDue;

    /** Whether the person's last statement bound the claim to statedDue. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The figure the last statement bound the claim to; null while unbound or when it had none. */
    @Column(name = "stated_due", precision = 14, scale = 2)
    private BigDecimal statedDue;

    /**
     * Whether statedDue is the figure as shown, late charge included; false for a claim bound before
     * statements bound that, whose statedDue is its figure without its late charge.
     */
    @Column(name = "stated_as_shown", nullable = false)
    private boolean statedAsShown;

    /** The number of the claim a correction corrects; null for any other claim. */
    @Column(name = "corrects")
    private String corrects;

    /** The date of the claim a correction corrects, which its rounding goes by; null for any other claim. */
    @Column(name = "corrects_date")
    private LocalDate correctsDate;

    /**
     * The date the claim is due by, fixed when it was added: the terms-th business day after its date, by the
     * terms then of its person's grade on its date and the holidays on the sheet then. A holiday added later
     * fixes it again over the holidays then, with the same terms (see {@link #terms}).
     */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    /** The terms (in business days) the claim's due-by was fixed with. */
    @Column(name = "terms", nullable = false)
    private int terms;

    /** The date the claim is deferred until; null when it was never deferred. See {@link Dues#deferred}. */
    @Column(name = "deferred_until")
    private LocalDate deferredUntil;

    /** The last sheet's today a reminder was written for the claim; null when it was never reminded. */
    @Column(name = "reminded_on")
    private LocalDate remindedOn;

    /** The sheet's today when the claim last went to REVIEW; null when it never did. */
    @Column(name = "review_on")
    private LocalDate reviewOn;

    /** Whether a reminder ever escalated the claim; a claim is escalated at most once. */
    @Column(name = "escalated", nullable = false)
    private boolean escalated;

    /** Whether a steward waived the claim's late charge; a waived claim bears none from then on. */
    @Column(name = "waived", nullable = false)
    private boolean waived;

    /** Whether the claim is held: it still counts in every total. */
    @Column(name = "held", nullable = false)
    private boolean held;

    /** How many proofs the claim has; it starts at 0. */
    @Column(name = "proofs", nullable = false)
    private int proofs;

    /** How many of the claim's proofs are photos; the rest are receipts. */
    @Column(name = "proof_photos", nullable = false)
    private int proofPhotos;

    /** The number (as shown) of the claim this one was split off; null for any claim not split off another. */
    @Column(name = "split_from")
    private String splitFrom;

    /** The claim's external reference, unique on its sheet without regard to case; null when it has none. */
    @Column(name = "ref")
    private String ref;

    /** The claim's tags, sorted and comma separated; "" when it has none. */
    @Column(name = "tags", nullable = false)
    private String tags = "";

    /** The reason from the sheet's list the claim was voided with; null when it is not VOID or was voided without one. */
    @Column(name = "void_reason")
    private String voidReason;

    /** Whether the claim was added while a soft-closed period contained its due-by. */
    @Column(name = "late_entry", nullable = false)
    private boolean lateEntry;

    /**
     * The user whose open editor locks the claim for everyone else, until it is saved or cancelled or the
     * sheet's today changes; null when no editor holds it.
     */
    @Column(name = "locked_by")
    private String lockedBy;

    /** The claim's place in its person's list, newest first; the store takes it on adding and reassigning. */
    @Column(name = "listed", insertable = false, updatable = false)
    private Long listed;

    /** The claim's status log: one line per status it has had, oldest first. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_log", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "seq")
    private List<ClaimLogLine> log = new ArrayList<>();

    /** The claim's comments, oldest first. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_comment", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "seq")
    private List<ClaimComment> comments = new ArrayList<>();

    /** The claim's lines, in the order they were added; with any, its amount is theirs added up. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_line", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "seq")
    private List<ClaimLine> lines = new ArrayList<>();

    protected Claim() {
    }

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, String note, String corrects) {
        this.corrects = corrects;
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.date = date;
        this.note = note;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    /**
     * The claim as it arrives on another sheet for the person there: its status, dates, note, tags, frozen
     * figures, log, comments and lines; unnumbered (to be given the other sheet's next), released from any figure a
     * statement bound, without its reference, settle batch or editor lock.
     */
    public Claim arriving(String sheetId, Long personId) {
        Claim arrived = new Claim(sheetId, personId, amount, date, note, corrects);
        arrived.status = status;
        arrived.openedBy = openedBy;
        arrived.editedBy = editedBy;
        arrived.settledDue = settledDue;
        arrived.correctsDate = correctsDate;
        arrived.dueBy = dueBy;
        arrived.terms = terms;
        arrived.deferredUntil = deferredUntil;
        arrived.remindedOn = remindedOn;
        arrived.escalated = escalated;
        arrived.waived = waived;
        arrived.held = held;
        arrived.reviewOn = reviewOn;
        arrived.proofs = proofs;
        arrived.proofPhotos = proofPhotos;
        arrived.splitFrom = splitFrom;
        arrived.tags = tags;
        arrived.voidReason = voidReason;
        arrived.lateEntry = lateEntry;
        log.forEach(l -> arrived.log.add(new ClaimLogLine(l.getStatus(), l.getBy(), l.getOn(), l.getDue())));
        comments.forEach(c -> arrived.comments.add(new ClaimComment(c.getBy(), c.getOn(), c.getText())));
        lines.forEach(l -> arrived.lines.add(new ClaimLine(l.getLabel(), l.getQty(), l.getUnit())));
        return arrived;
    }

    /**
     * The claim as copied onto a clone of its sheet for the person there: as {@link #arriving}, keeping its
     * reference too.
     */
    /**
     * A copy rebuilt from a written-down sheet (never saved) carries the id the claim has in the store, so the
     * forward-only rates saved after it was added are left out of its figures as they are for the claim itself.
     */
    public Claim restoredAs(Long id) {
        this.id = id;
        return this;
    }

    public Claim cloned(String sheetId, Long personId) {
        Claim copy = arriving(sheetId, personId);
        copy.ref = ref;
        return copy;
    }

    public Long getListed() {
        return listed;
    }

    public Long getPersonId() {
        return personId;
    }

    public void setPersonId(Long personId) {
        this.personId = personId;
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

    public void setDueBy(LocalDate dueBy) {
        this.dueBy = dueBy;
    }

    public int getTerms() {
        return terms;
    }

    public void setTerms(int terms) {
        this.terms = terms;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public LocalDate getDeferredUntil() {
        return deferredUntil;
    }

    public void setDeferredUntil(LocalDate deferredUntil) {
        this.deferredUntil = deferredUntil;
    }

    public LocalDate getRemindedOn() {
        return remindedOn;
    }

    public void setRemindedOn(LocalDate remindedOn) {
        this.remindedOn = remindedOn;
    }

    public LocalDate getReviewOn() {
        return reviewOn;
    }

    public void setReviewOn(LocalDate reviewOn) {
        this.reviewOn = reviewOn;
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

    public boolean isEscalated() {
        return escalated;
    }

    public void setEscalated(boolean escalated) {
        this.escalated = escalated;
    }

    public int getProofs() {
        return proofs;
    }

    /** How many of the claim's proofs are of the kind (receipt or photo). */
    public int getProofs(String kind) {
        return PHOTO.equals(kind) ? proofPhotos : RECEIPT.equals(kind) ? proofs - proofPhotos : 0;
    }

    /** A proof kind: a receipt, the kind of a proof added with none chosen. */
    public static final String RECEIPT = "receipt";

    /** A proof kind: a photo. */
    public static final String PHOTO = "photo";

    /** Add a proof of the kind (receipt or photo): the proofs count is raised by one. */
    public void addProof(String kind) {
        proofs++;
        if (PHOTO.equals(kind)) {
            proofPhotos++;
        }
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

    public LocalDate getCorrectsDate() {
        return correctsDate;
    }

    public void setCorrectsDate(LocalDate correctsDate) {
        this.correctsDate = correctsDate;
    }

    /** The date the claim's rounding goes by: a correction's is the date of the claim it corrects. */
    public LocalDate getRoundsBy() {
        return correctsDate != null ? correctsDate : date;
    }

    /** Whether the claim is a correction: its amount is negative. */
    public boolean isCorrection() {
        return amount.signum() < 0;
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

    /** Log the status the claim has now, by a user on a date, with its due then. */
    public void logStatus(String by, LocalDate on, BigDecimal due) {
        log.add(new ClaimLogLine(status, by, on, due));
    }

    public List<ClaimComment> getComments() {
        return List.copyOf(comments);
    }

    /** Add a comment by a user on a date. */
    public void comment(String by, LocalDate on, String text) {
        comments.add(new ClaimComment(by, on, text));
    }

    public List<ClaimLine> getLines() {
        return List.copyOf(lines);
    }

    /** Whether the claim has lines, which its amount is then the sum of. */
    public boolean hasLines() {
        return !lines.isEmpty();
    }

    /** Add a line; the claim's amount becomes its lines' amounts added up. */
    public void addLine(String label, int qty, BigDecimal unit) {
        lines.add(new ClaimLine(label, qty, unit));
        amount = lines.stream().map(ClaimLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public Integer getNumber() {
        return number;
    }

    public void setNumber(Integer number) {
        this.number = number;
    }

    /** Number the claim sheet-wide within the fiscal year, shown <FY>-<n>. */
    public void setFiscalNumber(String fiscalYear, int n) {
        this.fiscalYear = fiscalYear;
        this.number = n;
    }

    public String getFiscalYear() {
        return fiscalYear;
    }

    /** Take away the claim's number, sheet-wide or within a period, so it can be given a new one. */
    public void clearNumber() {
        this.number = null;
        this.fiscalYear = null;
        this.periodSequence = null;
        this.periodSeqNumber = null;
        this.periodNumber = null;
    }

    /** Number the claim within a period sequence, shown as {@code label}. */
    public void setPeriodNumber(String sequence, int n, String label) {
        this.periodSequence = sequence;
        this.periodSeqNumber = n;
        this.periodNumber = label;
    }

    public String getPeriodSequence() {
        return periodSequence;
    }

    public Integer getPeriodSeqNumber() {
        return periodSeqNumber;
    }

    /** The number as shown: its period number, or its sheet-wide number; null while it has none. */
    public String getShownNumber() {
        return periodNumber != null ? periodNumber : number == null ? null
                : fiscalYear == null ? number.toString() : fiscalYear + "-" + number;
    }

    public Integer getSettleBatch() {
        return settleBatch;
    }

    public void setSettleBatch(Integer settleBatch) {
        this.settleBatch = settleBatch;
    }

    public String getSplitFrom() {
        return splitFrom;
    }

    public void setSplitFrom(String splitFrom) {
        this.splitFrom = splitFrom;
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
        this.tags = tags == null ? "" : tags;
    }

    public boolean isLateEntry() {
        return lateEntry;
    }

    public void setLateEntry(boolean lateEntry) {
        this.lateEntry = lateEntry;
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

    public BigDecimal getSettledDue() {
        return settledDue;
    }

    public void setSettledDue(BigDecimal settledDue) {
        this.settledDue = settledDue;
    }

    public boolean isStated() {
        return stated;
    }

    public BigDecimal getStatedDue() {
        return statedDue;
    }

    public boolean isStatedAsShown() {
        return statedAsShown;
    }

    /** Bind the claim to its figure as shown, late charge included (a statement it counts at). */
    public void bind(BigDecimal figure) {
        bind(figure, true);
    }

    /** Bind the claim to a figure, as shown or (bound before statements bound that) without its late charge. */
    public void bind(BigDecimal figure, boolean asShown) {
        this.stated = true;
        this.statedDue = figure;
        this.statedAsShown = asShown;
    }

    /** Release the claim to its live figure (a statement it does not count at). */
    public void unbind() {
        this.stated = false;
        this.statedDue = null;
        this.statedAsShown = false;
    }
}
