package net.officefloor.hq.app.claim;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A claim a person on a sheet makes: an amount (two decimals), a date, a note and a status (DRAFT
 * when added). A claim with a negative amount is a correction, naming the number of the SETTLED claim
 * of the same person it corrects.
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

    /** The date the claim is due by, fixed when it was added from its person's grade's terms. */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    @Column(name = "note", nullable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /** The sheet's number given to the claim when opened; null while DRAFT, and kept once VOID. */
    @Column(name = "claim_number")
    private String number;

    /** The due fixed when the claim was settled; null otherwise, or when the grade had no rate then. */
    @Column(name = "settled_due", precision = 14, scale = 2)
    private BigDecimal settledDue;

    /** The user who opened the claim; null until it is opened. */
    @Column(name = "opened_by")
    private String openedBy;

    /** Whether the claim is bound by its person's last statement. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The due figure the claim was bound to by the statement; null when unbound, or when it was n/a. */
    @Column(name = "stated_due", precision = 14, scale = 2)
    private BigDecimal statedDue;

    /** The number of the claim this one corrects; null unless it is a correction. */
    @Column(name = "corrects")
    private String corrects;

    /** The date the claim is deferred until; null when it was never deferred. */
    @Column(name = "deferred_until")
    private LocalDate deferredUntil;

    /** The claim's status log, oldest first. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "claim_id", nullable = false)
    @OrderBy("id")
    private List<ClaimLogLine> log = new ArrayList<>();

    protected Claim() {
    }

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, LocalDate dueBy, String note,
            String corrects) {
        this.dueBy = dueBy;
        this.sheetId = sheetId;
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

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getDate() {
        return date;
    }

    public LocalDate getDueBy() {
        return dueBy;
    }

    public String getNote() {
        return note;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public String getNumber() {
        return number;
    }

    public BigDecimal getSettledDue() {
        return settledDue;
    }

    public String getOpenedBy() {
        return openedBy;
    }

    /** Record the user who opened the claim. */
    public void openedBy(String user) {
        this.openedBy = user;
    }

    /** Fix the claim's due as it is settled. */
    public void settleDue(BigDecimal due) {
        this.settledDue = due;
    }

    /** Move the claim to a new status; the caller checks the move is allowed. */
    public void become(ClaimStatus status) {
        this.status = status;
    }

    /** Give the claim its number when it is opened; it keeps it from then on. */
    public void number(String number) {
        this.number = number;
    }

    public String getCorrects() {
        return corrects;
    }

    /** Whether the claim is a correction: its amount is below 0.00. */
    public boolean isCorrection() {
        return amount.signum() < 0;
    }

    /** Change the claim's amount, date, note and the number it corrects. */
    public void change(BigDecimal amount, LocalDate date, String note, String corrects) {
        this.amount = amount;
        this.date = date;
        this.note = note;
        this.corrects = corrects;
    }

    public boolean isStated() {
        return stated;
    }

    public BigDecimal getStatedDue() {
        return statedDue;
    }

    /** Bind the claim to its due figure (null when n/a) as a statement is taken. */
    public void bind(BigDecimal due) {
        this.stated = true;
        this.statedDue = due;
    }

    /** Move the claim to another person on the sheet. */
    public void reassign(Long personId) {
        this.personId = personId;
    }

    /** Leave the claim unbound, its due live again. */
    public void unbind() {
        this.stated = false;
        this.statedDue = null;
    }

    public LocalDate getDeferredUntil() {
        return deferredUntil;
    }

    /** Defer the claim until a date; the caller checks the deferral is allowed. */
    public void defer(LocalDate until) {
        this.deferredUntil = until;
    }

    /**
     * Whether the claim is deferred on a date (the sheet's today): it is OPEN and the date has not yet
     * reached the date it is deferred until.
     */
    public boolean isDeferredOn(LocalDate today) {
        return status == ClaimStatus.OPEN && deferredUntil != null && today != null && today.isBefore(deferredUntil);
    }

    public List<ClaimLogLine> getLog() {
        return List.copyOf(log);
    }

    /** Add a line to the claim's status log for the status it now has. */
    public void logged(String by, LocalDate on, BigDecimal due) {
        log.add(new ClaimLogLine(status, by, on, due));
    }
}
