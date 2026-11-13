package net.officefloor.hq.app.claim;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import net.officefloor.hq.app.history.History;

/** A claim by a person on a sheet: an amount (two decimals), a date, a note, a status and, once opened,
 *  a number and who opened it. A negative amount is a correction, naming the number of the claim it corrects. */
@Entity
@EntityListeners(History.class)
@Table(name = "claim")
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    /** Its place in the sheet's list, newest (highest) first: set when added, and again when reassigned. */
    @Column(name = "list_order", nullable = false)
    private long listOrder;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "claim_date", nullable = false)
    private LocalDate date;

    /** The day it is due by: fixed when it is added, from the terms of its person's grade in force on its
     *  date then, counted as that many business days after its date (skipping weekends and the sheet's
     *  holidays listed then); it does not follow later changes to the terms or its date. Adding a holiday
     *  works it out again, with the same terms, while the claim is not SETTLED or VOID. */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    /** The terms its due-by was fixed with: that many business days after its date. */
    @Column(name = "terms", nullable = false)
    private int terms;

    @Column(name = "note", nullable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /** A correction's: the number of the SETTLED claim of the same person it corrects; none otherwise. */
    @Column(name = "corrects_number")
    private String corrects;

    /** Given when the claim is opened; none while a draft or once voided. */
    @Column(name = "claim_number")
    private String number;

    /** Fixed when the claim is settled, if its grade had a rate then; none otherwise. */
    @Column(name = "settled_due")
    private BigDecimal settledDue;

    /** The user who opened the claim; none while a draft. */
    @Column(name = "opened_by")
    private String openedBy;

    /** The user who last edited the claim; none while never edited. */
    @Column(name = "edited_by")
    private String editedBy;

    /** Bound by its person's last statement, which counted it: it shows and totals its stated due. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The figure it had when its person's last statement bound it; none when that was n/a, or unbound. */
    @Column(name = "stated_due")
    private BigDecimal statedDue;

    /** The date an OPEN claim is deferred until; none when never deferred. */
    @Column(name = "deferred_until")
    private LocalDate deferredUntil;

    /** How many proofs it has: raised by one at a time. */
    @Column(name = "proofs", nullable = false)
    private int proofs;

    /** The number of the claim it was split from; none when it was not split off another. */
    @Column(name = "split_from")
    private String splitFrom;

    /** The number of the bulk settle batch that settled it; none when settled one at a time, or never. */
    @Column(name = "settle_batch")
    private Integer settleBatch;

    /** The user whose editor is open on it, locking it for every other user; none while unlocked. */
    @Column(name = "locked_by")
    private String lockedBy;

    /** Whether it was added (or split off) while its period, the one containing its due-by, was soft-closed. */
    @Column(name = "late_entry", nullable = false)
    private boolean lateEntry;

    /** The last day (the sheet's today then) a reminder was written for it; none when never reminded. */
    @Column(name = "reminded_on")
    private LocalDate remindedOn;

    /** Whether a reminder escalated it; a claim is escalated once ever. */
    @Column(name = "escalated", nullable = false)
    private boolean escalated;

    /** Its external reference, unique on its sheet without regard to case; none when not given. */
    @Column(name = "ref")
    private String ref;

    /** The reason it was voided with, from the sheet's list; none when not voided, or voided without one. */
    @Column(name = "void_reason")
    private String voidReason;

    /** Its tags, sorted and comma separated; none when it carries none. */
    @Column(name = "tags")
    private String tags;

    /** Its status log: one line per status it has had, oldest first. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_status_log", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "seq")
    private List<StatusChange> statusLog = new ArrayList<>();

    /** Its comments, in the order written. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_comment", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "seq")
    private List<ClaimComment> comments = new ArrayList<>();

    /** Its lines, in the order added; while it has any, its amount is what they add up to. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_line", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "seq")
    private List<ClaimLine> lines = new ArrayList<>();

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

    /** A copy of the claim for a person on another sheet, listed at a place there: its status, dates, note,
     *  terms, frozen figure, tags, status log, comments and lines carried; no number (one is given there), no figure
     *  a statement bound, no reference, settle batch or lock. */
    public Claim copyTo(String otherSheetId, Long otherPersonId, long otherListOrder) {
        Claim copy = new Claim(otherSheetId, otherPersonId, amount, date, dueBy, note, corrects);
        copy.listOrder = otherListOrder;
        copy.terms = terms;
        copy.status = status;
        copy.settledDue = settledDue;
        copy.stated = stated;
        copy.statedDue = statedDue;
        copy.openedBy = openedBy;
        copy.editedBy = editedBy;
        copy.deferredUntil = deferredUntil;
        copy.proofs = proofs;
        copy.splitFrom = splitFrom;
        copy.lateEntry = lateEntry;
        copy.remindedOn = remindedOn;
        copy.escalated = escalated;
        copy.voidReason = voidReason;
        copy.tags = tags;
        statusLog.forEach(l -> copy.statusLog.add(new StatusChange(l.getStatus(), l.getBy(), l.getOn(), l.getDue())));
        comments.forEach(c -> copy.comments.add(new ClaimComment(c.getBy(), c.getOn(), c.getText())));
        lines.forEach(l -> copy.lines.add(new ClaimLine(l.getLabel(), l.getQty(), l.getUnit())));
        return copy;
    }

    /** The sheet's today when it was first opened, from its status log; none when never logged as opened. */
    public LocalDate openedOn() {
        return statusLog.stream().filter(l -> l.getStatus() == ClaimStatus.OPEN).map(StatusChange::getOn)
                .findFirst().orElse(null);
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

    /** Move the claim to another person on the sheet. */
    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    public long getListOrder() {
        return listOrder;
    }

    public void setListOrder(long listOrder) {
        this.listOrder = listOrder;
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

    /** A correction: its amount is negative. Its due has no floor and no late charge. */
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

    public void setDueBy(LocalDate dueBy) {
        this.dueBy = dueBy;
    }

    public int getTerms() {
        return terms;
    }

    public void setTerms(int terms) {
        this.terms = terms;
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

    /** Its tags, sorted; empty when it carries none. */
    public List<String> getTags() {
        return tags == null ? List.of() : List.of(tags.split(","));
    }

    /** Carry the tags (normalised: trimmed, lower-cased, without repeats and sorted). */
    public void setTags(List<String> tags) {
        this.tags = tags.isEmpty() ? null : String.join(",", tags);
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

    public boolean isStated() {
        return stated;
    }

    /** Bound by its person's last statement now: stated, and not PENDING (a PENDING claim shows and totals
     *  its live figure; rejected back to OPEN, it is bound again). */
    public boolean isBound() {
        return stated && status != ClaimStatus.PENDING;
    }

    public BigDecimal getStatedDue() {
        return statedDue;
    }

    /** Bind the claim to a figure (none for n/a), or unbind it. */
    public void setStated(boolean stated, BigDecimal statedDue) {
        this.stated = stated;
        this.statedDue = stated ? statedDue : null;
    }

    public LocalDate getDeferredUntil() {
        return deferredUntil;
    }

    public void setDeferredUntil(LocalDate deferredUntil) {
        this.deferredUntil = deferredUntil;
    }

    /** Deferred on a day: OPEN, with a deferred-until date after it. From that date on it is an OPEN claim
     *  like any other. */
    public boolean deferredOn(LocalDate today) {
        return status == ClaimStatus.OPEN && deferredUntil != null && today != null && today.isBefore(deferredUntil);
    }

    public int getProofs() {
        return proofs;
    }

    /** Raise its proofs count by one. */
    public void addProof() {
        proofs++;
    }

    public String getSplitFrom() {
        return splitFrom;
    }

    public void setSplitFrom(String splitFrom) {
        this.splitFrom = splitFrom;
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

    public boolean isLateEntry() {
        return lateEntry;
    }

    public void setLateEntry(boolean lateEntry) {
        this.lateEntry = lateEntry;
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

    public List<StatusChange> getStatusLog() {
        return List.copyOf(statusLog);
    }

    /** Add a line to its status log, for the status it has now. */
    public void logStatus(String by, LocalDate on, BigDecimal due) {
        statusLog.add(new StatusChange(status, by, on, due));
    }

    public List<ClaimComment> getComments() {
        return List.copyOf(comments);
    }

    /** Add a comment after those it has. */
    public void addComment(String by, LocalDate on, String text) {
        comments.add(new ClaimComment(by, on, text));
    }

    public List<ClaimLine> getLines() {
        return List.copyOf(lines);
    }

    /** Add a line after those it has; its amount becomes what its lines add up to. */
    public void addLine(String label, int qty, BigDecimal unit) {
        lines.add(new ClaimLine(label, qty, unit));
        amount = lines.stream().map(ClaimLine::total).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
    }
}
