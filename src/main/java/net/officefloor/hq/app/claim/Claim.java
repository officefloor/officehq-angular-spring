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

    /** The day the claim is due by: fixed when it is added, from its date and the terms of its person's grade in
     *  force then; it does not change when the terms or its date change later. */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

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

    /** Whether the claim is bound by its person's last statement. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The figure the claim had at its person's last statement, while bound by it; none if it had none then. */
    @Column(name = "stated_due", precision = 14, scale = 2)
    private BigDecimal statedDue;

    /** The number of the SETTLED claim of the same person a correction corrects; none on any other claim. */
    @Column(name = "corrects")
    private String corrects;

    /** The day an OPEN claim is deferred until; none if it was never deferred. */
    @Column(name = "deferred_until")
    private LocalDate deferredUntil;

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

    /** Move the claim to another person, listed as their newest claim. */
    public void reassign(Long personId, long listed) {
        this.personId = personId;
        this.listed = listed;
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

    /** Whether a statement binds the claim now: it is stated and not PENDING (a PENDING claim follows its live
     *  figure). A bound claim keeps its bound figure, even when it had none (n/a). */
    public boolean isBound() {
        return stated && status != ClaimStatus.PENDING;
    }

    /** The figure a statement binds the claim to now: its stated one, except while PENDING, when no statement
     *  binds it and it follows its live figure; null when not bound, or bound with no figure (n/a). */
    public BigDecimal getBoundDue() {
        return status == ClaimStatus.PENDING ? null : statedDue;
    }

    /** Bind the claim to the figure it has at a statement. */
    public void bind(BigDecimal figure) {
        this.stated = true;
        this.statedDue = figure;
    }

    /** Leave the claim unbound by a statement, so it is live again. */
    public void unbind() {
        this.stated = false;
        this.statedDue = null;
    }

    public LocalDate getDeferredUntil() {
        return deferredUntil;
    }

    public void setDeferredUntil(LocalDate deferredUntil) {
        this.deferredUntil = deferredUntil;
    }

    /** Whether the claim is deferred on this day: it is OPEN and deferred until a later day. While deferred it
     *  counts in no total, is not overdue, cannot be settled and is left unbound by a statement. */
    public boolean isDeferredOn(LocalDate today) {
        return status == ClaimStatus.OPEN && deferredUntil != null && today != null && today.isBefore(deferredUntil);
    }

    /** The claim's status log, oldest first. */
    public List<ClaimLogEntry> getLog() {
        return Collections.unmodifiableList(log);
    }

    /** Log the status the claim has now, taken by this user on this day, at the due it has at this moment. */
    public void logStatus(String user, LocalDate on, BigDecimal due) {
        log.add(new ClaimLogEntry(status, user, on, due));
    }
}
