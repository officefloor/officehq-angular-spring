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

/** A claim by a person on a sheet: an amount (two decimals), a date, a note, a status and, once opened,
 *  a number and who opened it. A negative amount is a correction, naming the number of the claim it corrects. */
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

    /** Its place in the sheet's list, newest (highest) first: set when added, and again when reassigned. */
    @Column(name = "list_order", nullable = false)
    private long listOrder;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "claim_date", nullable = false)
    private LocalDate date;

    /** The day it is due by: fixed when it is added, from the terms of its person's grade in force on its
     *  date then; it does not follow later changes to the terms or its date. */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

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

    /** Its status log: one line per status it has had, oldest first. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_status_log", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "seq")
    private List<StatusChange> statusLog = new ArrayList<>();

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

    public List<StatusChange> getStatusLog() {
        return List.copyOf(statusLog);
    }

    /** Add a line to its status log, for the status it has now. */
    public void logStatus(String by, LocalDate on, BigDecimal due) {
        statusLog.add(new StatusChange(status, by, on, due));
    }
}
