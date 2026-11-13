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

    /** Whether the claim counts toward what is claimed and due: only OPEN, PENDING and SETTLED ones do. */
    public boolean counts() {
        return status.counts();
    }

    /** Whether the claim is held: it is OPEN and dated within one of its person's holds. */
    public boolean heldBy(List<PersonHold> holds) {
        return status == ClaimStatus.OPEN && holds.stream().anyMatch(hold -> hold.contains(date));
    }

    /** Whether the claim counts in live totals given its person's holds: one that counts, unless it
     *  is held and not bound by a statement (a bound claim stays in whatever its date). */
    public boolean countsLive(List<PersonHold> holds) {
        return counts() && (stated || !heldBy(holds));
    }
}
