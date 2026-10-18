package net.officefloor.hq.app.claim;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.FetchType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** A claim a person on a sheet makes. */
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

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "claim_date", nullable = false)
    private LocalDate date;

    /** Fixed when the claim is created: its date plus the terms of its person's grade in force on it then. */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    @Column(name = "note", nullable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /** Given when the claim is opened; none while a draft; a voided claim keeps it (never reused). */
    @Column(name = "number")
    private String number;

    /** The user who opened the claim; none while a draft. They may not settle it. */
    @Column(name = "opened_by")
    private String openedBy;

    /** Fixed when the claim is settled; none while live, or when its grade had no rate then (it stays live). */
    @Column(name = "due", precision = 15, scale = 2)
    private BigDecimal due;

    /** Bound by its person's last statement: it shows and totals {@code statedDue} until the next one. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The figure the claim had at its person's last statement, when bound; none when it was n/a. */
    @Column(name = "stated_due", precision = 15, scale = 2)
    private BigDecimal statedDue;

    /** A correction's (negative amount) number of the settled claim of the same person it corrects; none otherwise. */
    @Column(name = "corrects")
    private String corrects;

    /** One entry per status the claim has had, oldest first (its DRAFT one written when it was added). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_log", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "seq")
    private List<ClaimLogEntry> log = new ArrayList<>();

    protected Claim() {}

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, LocalDate dueBy, String note,
            String corrects) {
        this.corrects = corrects;
        this.dueBy = dueBy;
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.date = date;
        this.note = note;
    }

    public Long getId() { return id; }

    public String getSheetId() { return sheetId; }

    public Long getPersonId() { return personId; }

    public void setPersonId(Long personId) { this.personId = personId; }

    public BigDecimal getAmount() { return amount; }

    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public LocalDate getDate() { return date; }

    public void setDate(LocalDate date) { this.date = date; }

    public LocalDate getDueBy() { return dueBy; }

    public String getNote() { return note; }

    public void setNote(String note) { this.note = note; }

    public ClaimStatus getStatus() { return status; }

    public void setStatus(ClaimStatus status) { this.status = status; }

    public String getNumber() { return number; }

    public void setNumber(String number) { this.number = number; }

    public String getOpenedBy() { return openedBy; }

    public void setOpenedBy(String openedBy) { this.openedBy = openedBy; }

    public BigDecimal getDue() { return due; }

    public void setDue(BigDecimal due) { this.due = due; }

    public String getCorrects() { return corrects; }

    public void setCorrects(String corrects) { this.corrects = corrects; }

    /** A correction: a negative amount; its due has no floor and no late charge. */
    public boolean isCorrection() {
        return amount.signum() < 0;
    }

    public boolean isStated() { return stated; }

    public BigDecimal getStatedDue() { return statedDue; }

    /** Whether the claim keeps a figure that does not follow the rates: a settled due, or a bound one (not n/a). */
    public boolean keepsFigure() {
        return due != null || (stated && statedDue != null);
    }

    /** Bind the claim to this figure (null: n/a). */
    public void bind(BigDecimal figure) {
        this.stated = true;
        this.statedDue = figure;
    }

    /** Leave the claim unbound: its figure is live again. */
    public void unbind() {
        this.stated = false;
        this.statedDue = null;
    }

    public List<ClaimLogEntry> getLog() { return List.copyOf(log); }

    /** Log the status the claim has now, given by this user on this date with this due (null: n/a). */
    public void logStatus(String user, LocalDate on, BigDecimal due) {
        log.add(new ClaimLogEntry(status, user, on, due));
    }
}
