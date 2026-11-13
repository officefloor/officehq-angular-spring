package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.person.PersonHold;

/** A claim a person on a sheet makes: an amount (two decimals), a date, a note and a status (DRAFT
 *  when added). A claim with a negative amount is a correction, naming the number of the SETTLED
 *  claim of the same person it corrects. */
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

    /** When the claim was listed under its person, from the database's sequence: when it was added,
     *  or last reassigned. Claims are listed newest first by it; set by the database only. */
    @Column(name = "listed", insertable = false, updatable = false)
    private Long listed;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "claim_date", nullable = false)
    private LocalDate date;

    /** The date the claim is due by, fixed when it was added: its date plus the terms of its person's
     *  grade in force on that date. It stays as it was when the terms or the claim's date change. */
    @Column(name = "due_by", nullable = false, updatable = false)
    private LocalDate dueBy;

    @Column(name = "note", nullable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /** The claim's number, given when it is opened and kept when it is voided (never reused): the
     *  sheet-wide number (1, 2, ...), or under period numbering <period>-<n> or X-<n>; null for a
     *  claim never opened. */
    @Column(name = "claim_number")
    private String number;

    /** The due fixed when the claim was settled; null until then, or when its grade had no rate. */
    @Column(name = "settled_due", precision = 12, scale = 2)
    private BigDecimal settledDue;

    /** The user who opened the claim; null for a claim never opened. */
    @Column(name = "opened_by")
    private String openedBy;

    /** Whether the person's last statement bound the claim to the figure it had then. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The figure the claim was bound to by a statement; null when unbound or it had no figure. */
    @Column(name = "stated_due", precision = 12, scale = 2)
    private BigDecimal statedDue;

    /** The number of the claim this correction corrects; null for a claim that is not a correction. */
    @Column(name = "corrects_number")
    private String corrects;

    /** The date an OPEN claim is deferred until; null when it was never deferred. */
    @Column(name = "deferred_until")
    private LocalDate deferredUntil;

    /** How many proofs have been added to the claim. */
    @Column(name = "proofs", nullable = false)
    private int proofs;

    /** The number of the claim this claim was split off; null for a claim not split off another. */
    @Column(name = "split_from", updatable = false)
    private String splitFrom;

    /** The user whose open editor locks the claim for everyone else; null when no one's does. */
    @Column(name = "locked_by")
    private String lockedBy;

    /** Whether the claim was added while the period its date falls in was soft-closed. */
    @Column(name = "late_entry", nullable = false, updatable = false)
    private boolean lateEntry;

    protected Claim() {
    }

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, LocalDate dueBy, String note,
            String corrects) {
        this.sheetId = sheetId;
        this.dueBy = dueBy;
        this.corrects = corrects;
        this.personId = personId;
        this.amount = amount;
        this.date = date;
        this.note = note;
    }

    /** An OPEN claim split off another: the split-off amount, with the same date and note, its own
     *  due-by, number and opener, naming the claim it was split from. */
    public static Claim splitOff(Claim from, BigDecimal amount, LocalDate dueBy, String number, String openedBy) {
        Claim split = new Claim(from.sheetId, from.personId, amount, from.date, dueBy, from.note, null);
        split.status = ClaimStatus.OPEN;
        split.number = number;
        split.openedBy = openedBy;
        split.splitFrom = from.number;
        return split;
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

    /** Bind the claim to a figure (may be null: it had none). */
    public void bind(BigDecimal due) {
        this.stated = true;
        this.statedDue = due;
    }

    /** Leave the claim unbound, its figure live again. */
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

    public int getProofs() {
        return proofs;
    }

    public String getSplitFrom() {
        return splitFrom;
    }

    public boolean isLateEntry() {
        return lateEntry;
    }

    /** Mark the claim, before it is first saved, as added while its period was soft-closed. */
    public void markLateEntry() {
        this.lateEntry = true;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    /** Lock the claim to a user's editor, or release it (null). */
    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    /** Whether the claim is locked by a user other than this one. */
    public boolean lockedAgainst(String user) {
        return lockedBy != null && !lockedBy.equals(user);
    }

    /** Count one more proof added to the claim. */
    public void addProof() {
        this.proofs++;
    }

    /** Whether the claim is deferred on a day: it is OPEN and that day is before its deferred-until
     *  date. From that date on it is an OPEN claim like any other. */
    public boolean deferredOn(LocalDate today) {
        return status == ClaimStatus.OPEN && deferredUntil != null && today.isBefore(deferredUntil);
    }

    /** Whether the claim counts toward what is claimed and due: only OPEN, PENDING and SETTLED ones do. */
    public boolean counts() {
        return status.counts();
    }

    /** Whether the claim is held: it is OPEN and dated within one of its person's holds. */
    public boolean heldBy(List<PersonHold> holds) {
        return status == ClaimStatus.OPEN && holds.stream().anyMatch(hold -> hold.contains(date));
    }

    /** Whether the claim is left out for its person's retirement: it is OPEN or PENDING and the
     *  sheet's today is on or after the date they are retired from (null: not retired). */
    public boolean retiredOn(LocalDate retiredFrom, LocalDate today) {
        return status.isOpen() && retiredFrom != null && !today.isBefore(retiredFrom);
    }

    /** Whether the claim counts in live totals on the sheet's today given its person's holds and
     *  retirement date: one that counts and is not deferred, unless it is held, or OPEN or PENDING once
     *  its person is retired, and not bound by a statement (a bound claim stays in whatever its date). */
    public boolean countsLive(List<PersonHold> holds, LocalDate retiredFrom, LocalDate today) {
        return counts() && !deferredOn(today) && (stated || !(heldBy(holds) || retiredOn(retiredFrom, today)));
    }

    /** Whether the claim counts in a period's live figures, given the status it had at the end of the
     *  period's last day (null when it was added after): that status counts, and the live rules leave
     *  it in (not deferred on the sheet's today, and not held or retired out unless bound by a statement). */
    public boolean countsAsOf(ClaimStatus then, List<PersonHold> holds, LocalDate retiredFrom, LocalDate today) {
        return then != null && then.counts() && !deferredOn(today)
                && (stated || !(heldBy(holds) || retiredOn(retiredFrom, today)));
    }
}
