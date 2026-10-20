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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

/** A claim a person makes on a sheet: an amount (negative for a correction), a date, a note, a status and, once opened, a number. */
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

    /** The date the claim is due by, fixed when it is added: its date plus the terms of its person's grade then. */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    @Column(name = "note", nullable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /** The sheet's number for the claim, given when it is opened and kept once voided; none while a draft. It is
     * the sheet-wide number, or with period numbering <period name>-<n>, or X-<n> in no period. */
    @Column(name = "claim_number")
    private String number;

    /** What was due on the claim when it was settled; none until then, or if its grade had no rate. */
    @Column(name = "settled_due", precision = 12, scale = 2)
    private BigDecimal settledDue;

    /** Who opened the claim; none while it is a draft. */
    @Column(name = "opened_by")
    private String openedBy;

    /** Whether the claim is bound to the figure it had at its person's last statement. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The figure the claim was bound to at its person's last statement; none when it was n/a then. */
    @Column(name = "stated_due", precision = 12, scale = 2)
    private BigDecimal statedDue;

    /** The number of the settled claim of the same person a correction corrects; none for any other claim. */
    @Column(name = "corrects")
    private String corrects;

    /** The date an OPEN claim is deferred until; none when it was never deferred. */
    @Column(name = "deferred_until")
    private LocalDate deferredUntil;

    /** The number of the claim this one was split off; none for any other claim. */
    @Column(name = "split_from")
    private String splitFrom;

    /** How many proofs the claim has; 0 until one is added. */
    @Column(name = "proofs", nullable = false)
    private int proofs;

    /** Who has the claim's editor open; none when no one has. */
    @Column(name = "locked_by")
    private String lockedBy;

    /** The sheet's today when the editor was opened; the lock lapses once the sheet's today is another. */
    @Column(name = "locked_on")
    private LocalDate lockedOn;

    /** Whether the claim was added while the period it is dated in was soft-closed. */
    @Column(name = "late_entry", nullable = false)
    private boolean lateEntry;

    /** The claim's status log, oldest first: one line per status it has had, from the DRAFT it was added as. */
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "claim_status_log", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "seq")
    private List<StatusLogLine> statusLog = new ArrayList<>();

    /** The numbers the claim had before it was re-dated into another period's sequence; none is given out again. */
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "claim_former_number", joinColumns = @JoinColumn(name = "claim_id"))
    @Column(name = "former_number")
    private Set<String> formerNumbers = new TreeSet<>();

    protected Claim() {}

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, LocalDate dueBy, String note) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.date = date;
        this.dueBy = dueBy;
        this.note = note;
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

    public Set<String> getFormerNumbers() {
        return Collections.unmodifiableSet(formerNumbers);
    }

    /** Give the claim a new number; the one it had is kept as a former number, never given out again. */
    public void renumber(String next) {
        if (number != null) {
            formerNumbers.add(number);
        }
        this.number = next;
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

    public void setCorrects(String corrects) {
        this.corrects = corrects;
    }

    /** Whether the claim is a correction: its amount is negative. */
    public boolean isCorrection() {
        return amount.signum() < 0;
    }

    public boolean isStated() {
        return stated;
    }

    public BigDecimal getStatedDue() {
        return statedDue;
    }

    /** Bind the claim to a figure (none for n/a). */
    public void bind(BigDecimal due) {
        this.stated = true;
        this.statedDue = due;
    }

    /** Leave the claim unbound, live again. */
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

    /** Whether the claim is deferred on a day: it is still open and that day has not reached the date it is
     * deferred until. Such a claim counts in no total, is not overdue and cannot be settled. */
    public boolean deferredOn(LocalDate today) {
        return status.isOpen() && deferredUntil != null && today != null && today.isBefore(deferredUntil);
    }

    public String getSplitFrom() {
        return splitFrom;
    }

    public void setSplitFrom(String splitFrom) {
        this.splitFrom = splitFrom;
    }

    public int getProofs() {
        return proofs;
    }

    /** Raise the claim's proofs count by one. */
    public void addProof() {
        proofs++;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public LocalDate getLockedOn() {
        return lockedOn;
    }

    /** Who holds the claim's lock on the sheet's today; none when no one does, or the lock has lapsed. */
    public String lockHolderOn(LocalDate today) {
        return lockedBy != null && lockedOn != null && lockedOn.equals(today) ? lockedBy : null;
    }

    /** Lock the claim for the user, from the sheet's today. */
    public void lock(String user, LocalDate today) {
        this.lockedBy = user;
        this.lockedOn = today;
    }

    public boolean isLateEntry() {
        return lateEntry;
    }

    public void setLateEntry(boolean lateEntry) {
        this.lateEntry = lateEntry;
    }

    public void unlock() {
        this.lockedBy = null;
        this.lockedOn = null;
    }

    public List<StatusLogLine> getStatusLog() {
        return Collections.unmodifiableList(statusLog);
    }

    /** The status the claim had at the end of a day, as its status log records it (its lines are dated by the
     * sheet's today when each change was made): that of the last line on or before it; none when the claim was
     * added after it. A claim with no log (one there before the log was kept) has the status it has now. */
    public ClaimStatus statusAt(LocalDate date) {
        if (statusLog.isEmpty()) {
            return status;
        }
        ClaimStatus then = null;
        for (StatusLogLine line : statusLog) {
            if (!line.getOn().isAfter(date)) {
                then = line.getStatus();
            }
        }
        return then;
    }

    /** Add a line to the status log for the status the claim has now. */
    public void logStatus(String by, LocalDate on, String due) {
        statusLog.add(new StatusLogLine(status, by, on, due));
    }
}
