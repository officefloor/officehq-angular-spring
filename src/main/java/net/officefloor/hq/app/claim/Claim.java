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

    /** The due fixed when the claim was settled; null while not settled or when there was no rate. */
    @Column(name = "settled_due", precision = 14, scale = 2)
    private BigDecimal settledDue;

    /** Whether the person's last statement bound the claim to statedDue. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The figure the last statement bound the claim to; null while unbound or when it had none. */
    @Column(name = "stated_due", precision = 14, scale = 2)
    private BigDecimal statedDue;

    /** The number of the claim a correction corrects; null for any other claim. */
    @Column(name = "corrects")
    private String corrects;

    /** The date of the claim a correction corrects, which its rounding goes by; null for any other claim. */
    @Column(name = "corrects_date")
    private LocalDate correctsDate;

    /** The date the claim is due by, fixed when it was added from the terms then of its person's grade on its date. */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    /** The date the claim is deferred until; null when it was never deferred. See {@link Dues#deferred}. */
    @Column(name = "deferred_until")
    private LocalDate deferredUntil;

    /** How many proofs the claim has; it starts at 0. */
    @Column(name = "proofs", nullable = false)
    private int proofs;

    /** The claim's place in its person's list, newest first; the store takes it on adding and reassigning. */
    @Column(name = "listed", insertable = false, updatable = false)
    private Long listed;

    /** The claim's status log: one line per status it has had, oldest first. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_log", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "seq")
    private List<ClaimLogLine> log = new ArrayList<>();

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

    public LocalDate getDeferredUntil() {
        return deferredUntil;
    }

    public void setDeferredUntil(LocalDate deferredUntil) {
        this.deferredUntil = deferredUntil;
    }

    public int getProofs() {
        return proofs;
    }

    /** Add a proof: the proofs count is raised by one. */
    public void addProof() {
        proofs++;
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

    public Integer getNumber() {
        return number;
    }

    public void setNumber(Integer number) {
        this.number = number;
    }

    /** Number the claim within a period sequence, shown as {@code label}. */
    public void setPeriodNumber(String sequence, int n, String label) {
        this.periodSequence = sequence;
        this.periodSeqNumber = n;
        this.periodNumber = label;
    }

    /** The number as shown: its period number, or its sheet-wide number; null while it has none. */
    public String getShownNumber() {
        return periodNumber != null ? periodNumber : number == null ? null : number.toString();
    }

    public String getOpenedBy() {
        return openedBy;
    }

    public void setOpenedBy(String openedBy) {
        this.openedBy = openedBy;
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

    /** Bind the claim to a figure (a statement it counts at). */
    public void bind(BigDecimal figure) {
        this.stated = true;
        this.statedDue = figure;
    }

    /** Release the claim to its live figure (a statement it does not count at). */
    public void unbind() {
        this.stated = false;
        this.statedDue = null;
    }
}
