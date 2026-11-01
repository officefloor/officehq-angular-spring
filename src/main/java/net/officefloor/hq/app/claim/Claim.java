package net.officefloor.hq.app.claim;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hibernate.annotations.BatchSize;

/** A claim a person on a sheet has made: an amount (two decimals), a date, a note and a status. A negative amount
 *  makes it a correction of the SETTLED claim of the same person whose number it names. */
@Entity
@Table(name = "claim")
public class Claim {

    /** The kinds a proof can be: a receipt (when none is chosen) or a photo. */
    public static final String PROOF_RECEIPT = "receipt";
    public static final String PROOF_PHOTO = "photo";
    public static final List<String> PROOF_KINDS = List.of(PROOF_RECEIPT, PROOF_PHOTO);

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

    /** The claim's external reference, unique on its sheet without regard to case; none when not given. */
    @Column(name = "ref", length = 120)
    private String ref;

    /** The claim's tags: trimmed, lower-cased, without repeats, sorted and joined by commas; none when it has none. */
    @Column(name = "tags", length = 1000)
    private String tags;

    /** The reason from the sheet's list the claim was voided with; none when it is not void or was voided
     *  without one. */
    @Column(name = "void_reason", length = 200)
    private String voidReason;

    /** The day the claim is due by: fixed when it is added: the terms-th business day after its date (skipping
     *  Saturdays, Sundays and the sheet's holidays then), on its person's own terms, else the terms of their grade
     *  then, else the sheet's then, each in force on the sheet's today when it is added; it does not change when the terms or its date
     *  change later. Adding a holiday on or before it fixes it again (unless the claim is SETTLED or VOID): from
     *  its date, on its terms, over the holidays on the sheet then. */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    /** Whether a steward set the due-by directly: it then stays as set, and a holiday added later does not fix it
     *  again. */
    @Column(name = "due_by_set", nullable = false)
    private boolean dueBySet;

    /** The terms the claim's due-by was fixed with: the number of business days after its date it is due by. */
    @Column(name = "terms", nullable = false)
    private int terms;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /** The claim's number on its sheet, given when it is opened: sheet-wide (1, 2, ...), or within its period
     *  (P1-1, or X-1 in no period) while the sheet numbers per period; none while a draft. A void claim keeps
     *  the number it had, and that number is never given again. */
    @Column(name = "claim_number")
    private String number;

    /** The due fixed when the claim was settled; none while not settled, or if its grade had no rate then. */
    @Column(name = "settled_due", precision = 14, scale = 2)
    private BigDecimal settledDue;

    /** The signed-in name of the user who opened the claim; none while a draft. */
    @Column(name = "opened_by", length = 100)
    private String openedBy;

    /** The signed-in name of the user who last edited the claim; none while it has never been edited. */
    @Column(name = "edited_by", length = 100)
    private String editedBy;

    /** The number of the settle batch that settled the claim; none when it was not settled by one. */
    @Column(name = "settle_batch")
    private Integer settleBatch;

    /** Whether the claim is bound by its person's last statement. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The figure the claim had at its person's last statement, while bound by it; none if it had none then. */
    @Column(name = "stated_due", precision = 14, scale = 2)
    private BigDecimal statedDue;

    /** The late charge the claim had at its person's last statement, while bound by it; none if it had no figure
     *  then, or was bound before late charges were bound (its late charge then follows the sheet's today). */
    @Column(name = "stated_late", precision = 14, scale = 2)
    private BigDecimal statedLate;

    /** The number of the SETTLED claim of the same person a correction corrects; none on any other claim. */
    @Column(name = "corrects")
    private String corrects;

    /** The number of the claim this one was split off; none on any other claim. */
    @Column(name = "split_from")
    private String splitFrom;

    /** The day an OPEN claim is deferred until; none if it was never deferred. */
    @Column(name = "deferred_until")
    private LocalDate deferredUntil;

    /** The last sheet's today on which a reminder was written for the claim; none if it was never reminded. */
    @Column(name = "reminded_on")
    private LocalDate remindedOn;

    /** Whether a reminder ever escalated the claim; a claim is escalated at most once. */
    @Column(name = "escalated", nullable = false)
    private boolean escalated;

    /** Whether a steward waived the claim's late charge; a waived claim bears none from then on. */
    @Column(name = "waived", nullable = false)
    private boolean waived;

    /** Whether the claim is held: while held, an OPEN or PENDING claim no statement binds counts in no live due figure (it still counts in what is claimed),
     *  as a held person's claims do. */
    @Column(name = "held", nullable = false)
    private boolean held;

    /** The user a steward assigned the claim to review (null when it was never assigned). */
    @Column(name = "review_assigned", length = 100)
    private String reviewAssigned;

    /** How many proofs have been given for the claim. */
    @Column(name = "proofs", nullable = false)
    private int proofs;

    /** How many of the claim's proofs are photos; the rest are receipts. */
    @Column(name = "proof_photos", nullable = false)
    private int proofPhotos;

    /** The signed-in name of the user who has the claim's editor open, locking it for other users until they save
     *  or cancel or the sheet's today changes; none when no one has. */
    @Column(name = "locked_by", length = 100)
    private String lockedBy;

    /** Whether the claim was added while the period its due-by is in was soft-closed. */
    @Column(name = "late_entry", nullable = false)
    private boolean lateEntry;

    /** When the claim was listed under its person, in the sheet's order: given when it is added and again when it
     *  is reassigned, so a person's claims list newest first by it. */
    @Column(name = "listed", nullable = false)
    private long listed;

    /** The claim's status log, oldest first: a line for each status it has had, the DRAFT it was added as first. */
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

    /** The claim's lines, in the order added; while it has any, its amount is their totals added up. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_line", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "seq")
    @BatchSize(size = 100)
    private List<ClaimLine> lines = new ArrayList<>();

    /** The claim's shares, in the order given: the other people holding a part of its due. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_share", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "seq")
    @BatchSize(size = 100)
    private List<ClaimShare> shares = new ArrayList<>();

    protected Claim() {
    }

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, LocalDate dueBy, String note,
            String corrects, long listed) {
        this.dueBy = dueBy;
        this.sheetId = sheetId;
        this.listed = listed;
        this.personId = personId;
        this.amount = amount;
        this.date = date;
        this.note = note;
        this.corrects = corrects;
    }

    /** A copy of the claim on another sheet for another person, unsaved and without a number yet: its status, frozen
     *  figure, dates, terms, note, reference, tags, proofs, comments, lines and log, listed where it was; no editor's lock,
     *  and no figure a statement bound it to. */
    public Claim copyTo(String sheetId, Long personId) {
        Claim copy = new Claim(sheetId, personId, amount, date, dueBy, note, corrects, listed);
        copy.ref = ref;
        copy.tags = tags;
        copy.voidReason = voidReason;
        copy.terms = terms;
        copy.dueBySet = dueBySet;
        copy.status = status;
        copy.settledDue = settledDue;
        copy.openedBy = openedBy;
        copy.editedBy = editedBy;
        copy.settleBatch = settleBatch;
        copy.splitFrom = splitFrom;
        copy.deferredUntil = deferredUntil;
        copy.remindedOn = remindedOn;
        copy.escalated = escalated;
        copy.waived = waived;
        copy.held = held;
        copy.reviewAssigned = reviewAssigned;
        copy.proofs = proofs;
        copy.proofPhotos = proofPhotos;
        copy.lateEntry = lateEntry;
        log.forEach(l -> copy.log.add(new ClaimLogEntry(l.getStatus(), l.getBy(), l.getOn(), l.getDue())));
        comments.forEach(c -> copy.comments.add(new ClaimComment(c.getBy(), c.getOn(), c.getText())));
        lines.forEach(l -> copy.lines.add(new ClaimLine(l.getLabel(), l.getQty(), l.getUnit())));
        return copy;
    }

    /** Name the claims this one corrects and was split off by their numbers on its own sheet. */
    public void renameLinks(String corrects, String splitFrom) {
        this.corrects = corrects;
        this.splitFrom = splitFrom;
    }

    public Long getId() {
        return id;
    }

    /** Give an unsaved claim rebuilt from the sheet's history the id it had then, so its figures are worked out as
     *  the live one's are; it is never saved. */
    public void rebuiltWithId(long id) {
        this.id = id;
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

    /** Move the claim to another person, listed as their newest claim. */
    public void reassign(Long personId, long listed) {
        this.personId = personId;
        this.listed = listed;
    }

    /** Move the claim to another sheet and person, listed there at {@code listed} and numbered {@code number}
     *  (none for a claim without one): it loses its reference, its settle batch and its editor's lock, keeping any
     *  figure a statement bound it to; a correction or split names the number given to the claim it names there. */
    public void transferTo(String sheetId, Long personId, long listed, String number, String corrects,
            String splitFrom) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.listed = listed;
        this.number = number;
        this.corrects = corrects;
        this.splitFrom = splitFrom;
        this.ref = null;
        this.settleBatch = null;
        this.lockedBy = null;
    }

    /** The sheet's today on which the claim was first opened (took a status past DRAFT), as its log records it;
     *  null when it never was. */
    public LocalDate openedOn() {
        return log.stream().filter(line -> line.getStatus() != ClaimStatus.DRAFT).map(ClaimLogEntry::getOn)
                .findFirst().orElse(null);
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

    /** Whether the claim is a correction: its amount is negative. */
    public boolean isCorrection() {
        return amount.signum() < 0;
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

    public boolean isDueBySet() {
        return dueBySet;
    }

    /** A steward sets the due-by directly; it stays as set. */
    public void setDueBy(LocalDate dueBy) {
        this.dueBy = dueBy;
        this.dueBySet = true;
    }

    public int getTerms() {
        return terms;
    }

    public void setTerms(int terms) {
        this.terms = terms;
    }

    /** Fix the claim's due-by again, on the terms it was fixed with. */
    public void refixDueBy(LocalDate dueBy) {
        this.dueBy = dueBy;
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

    /** Whether this user opened the claim or last edited it: either way, they cannot settle or approve it. */
    public boolean isOpenedOrEditedBy(String user) {
        return user.equals(openedBy) || user.equals(editedBy);
    }

    public boolean isStated() {
        return stated;
    }

    public BigDecimal getStatedDue() {
        return statedDue;
    }

    /** Whether a statement binds the claim now: it is stated, whatever its status (a PENDING claim too). A bound
     *  claim keeps its bound figure, even when it had none (n/a). */
    public boolean isBound() {
        return stated;
    }

    /** The figure a statement binds the claim to now: its stated one; null when not bound, or bound with no
     *  figure (n/a). */
    public BigDecimal getBoundDue() {
        return statedDue;
    }

    /** The late charge a statement binds the claim to now, with its figure; null when not bound, or bound with no
     *  late charge bound (it then follows the sheet's today). */
    public BigDecimal getBoundLate() {
        return statedLate;
    }

    /** Bind the claim to the figure and the late charge it has at a statement. */
    public void bind(BigDecimal figure, BigDecimal late) {
        this.stated = true;
        this.statedDue = figure;
        this.statedLate = figure == null ? null : late;
    }

    /** Leave the claim unbound by a statement, so it is live again. */
    public void unbind() {
        this.stated = false;
        this.statedDue = null;
        this.statedLate = null;
    }

    public String getSplitFrom() {
        return splitFrom;
    }

    /** Make the claim OPEN as one split off another: with this number (none until settled), opened by this user and naming the claim it was
     *  split from. */
    public void openAsSplit(String number, String openedBy, String splitFrom) {
        this.status = ClaimStatus.OPEN;
        this.number = number;
        this.openedBy = openedBy;
        this.splitFrom = splitFrom;
    }

    public LocalDate getDeferredUntil() {
        return deferredUntil;
    }

    public void setDeferredUntil(LocalDate deferredUntil) {
        this.deferredUntil = deferredUntil;
    }

    /** Whether the claim is deferred on this day: it is deferred until a later day and not bound by a statement (a
     *  deferral takes effect only once the binding ends), and is OPEN, or SETTLED while bound. While deferred it
     *  counts in no total, is not overdue, cannot be settled and is left unbound by a statement. */
    public boolean isDeferredOn(LocalDate today) {
        return (status == ClaimStatus.OPEN || status == ClaimStatus.SETTLED) && !stated && deferredUntil != null && today != null && today.isBefore(deferredUntil);
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

    /** The claim's tags, sorted; empty when it has none. */
    public List<String> tagList() {
        return tags == null ? List.of() : List.of(tags.split(","));
    }

    public LocalDate getRemindedOn() {
        return remindedOn;
    }

    /** Record that a reminder was written for the claim on this day. */
    public void remind(LocalDate on) {
        this.remindedOn = on;
    }

    public boolean isEscalated() {
        return escalated;
    }

    /** Record that a reminder escalated the claim. */
    public void escalate() {
        this.escalated = true;
    }

    public boolean isWaived() {
        return waived;
    }

    /** Record that a steward waived the claim's late charge. */
    public void waive() {
        this.waived = true;
    }

    public boolean isHeld() {
        return held;
    }

    public void setHeld(boolean held) {
        this.held = held;
    }

    public String getReviewAssigned() {
        return reviewAssigned;
    }

    public void setReviewAssigned(String reviewAssigned) {
        this.reviewAssigned = reviewAssigned;
    }

    public int getProofs() {
        return proofs;
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

    /** Mark the claim as added while its period was soft-closed. */
    public void markLateEntry() {
        this.lateEntry = true;
    }

    public int getProofPhotos() {
        return proofPhotos;
    }

    /** How many of the claim's proofs are receipts. */
    public int getProofReceipts() {
        return proofs - proofPhotos;
    }

    /** How many of the claim's proofs are of a kind (receipt or photo). */
    public int proofsOf(String kind) {
        return PROOF_PHOTO.equals(kind) ? proofPhotos : getProofReceipts();
    }

    /** Count one more proof given for the claim, of a kind (receipt or photo). */
    public void addProof(String kind) {
        proofs++;
        if (PROOF_PHOTO.equals(kind)) {
            proofPhotos++;
        }
    }

    /** The status the claim had at the end of {@code day}, as its log records it: the last line dated on or before
     *  it; null when the claim was added after it. */
    public ClaimStatus statusAt(LocalDate day) {
        ClaimStatus at = null;
        for (ClaimLogEntry line : log) {
            if (!line.getOn().isAfter(day)) {
                at = line.getStatus();
            }
        }
        return at;
    }

    /** The sheet's today on which the claim last became SETTLED, as its log records it; null when it never did. */
    public LocalDate settledOn() {
        LocalDate on = null;
        for (ClaimLogEntry line : log) {
            if (line.getStatus() == ClaimStatus.SETTLED) {
                on = line.getOn();
            }
        }
        return on;
    }

    /** The claim's status log, oldest first. */
    public List<ClaimLogEntry> getLog() {
        return Collections.unmodifiableList(log);
    }

    /** The day its log records it last deleted; null when it never was. */
    public LocalDate deletedOn() {
        LocalDate on = null;
        for (ClaimLogEntry entry : log) {
            if (entry.getStatus() == ClaimStatus.DELETED) {
                on = entry.getOn();
            }
        }
        return on;
    }

    /** Log the status the claim has now, taken by this user on this day, at the due it has at this moment. */
    public void logStatus(String user, LocalDate on, BigDecimal due) {
        log.add(new ClaimLogEntry(status, user, on, due));
    }

    /** The claim's comments, oldest first. */
    public List<ClaimComment> getComments() {
        return Collections.unmodifiableList(comments);
    }

    /** Add a comment written by this user on this day. */
    public void comment(String user, LocalDate on, String text) {
        comments.add(new ClaimComment(user, on, text));
    }

    /** The claim's shares, in the order given. */
    public List<ClaimShare> getShares() {
        return Collections.unmodifiableList(shares);
    }

    /** Give a person a share of the claim. */
    public void share(Long personId, BigDecimal share) {
        shares.add(new ClaimShare(personId, share));
    }

    /** The claim's holder's share for a person; null when they hold none. */
    public ClaimShare shareOf(Long personId) {
        return shares.stream().filter(s -> s.getPersonId().equals(personId)).findFirst().orElse(null);
    }

    /** The claim's lines, in the order added. */
    public List<ClaimLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    /** Add a line; the claim's amount becomes its lines' totals added up. */
    public void addLine(String label, int qty, BigDecimal unit) {
        lines.add(new ClaimLine(label, qty, unit));
        amount = lines.stream().map(ClaimLine::total).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
    }
}
