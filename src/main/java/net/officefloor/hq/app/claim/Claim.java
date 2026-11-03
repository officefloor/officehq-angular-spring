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
import org.hibernate.annotations.BatchSize;

/**
 * A claim by a person on a sheet: an amount (two decimals; below zero only for a correction, which
 * names the number of the settled claim it corrects), a date, a note and a status, once opened its number on the sheet and who opened it, and once settled the due fixed at
 * that moment, and while bound by its person's last statement the figure it had then.
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

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "claim_date", nullable = false)
    private LocalDate date;

    @Column(name = "note", nullable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /**
     * The claim's number on its sheet, given when it is opened and kept once VOID; none while DRAFT.
     * Sheet-wide ({@code 1}, {@code 2}, ...), or under numbering per period {@code <period name>-<n>}
     * or {@code X-<n>} in no period.
     */
    @Column(name = "claim_number")
    private String number;

    /** The due fixed when the claim was settled; none while not settled or if its grade had no rate. */
    @Column(name = "settled_due")
    private BigDecimal settledDue;

    /** The user who opened the claim; none while DRAFT, or if voided without being opened. */
    @Column(name = "opened_by")
    private String openedBy;

    /** Whether the person's last statement bound this claim to the figure it had then. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The figure bound by the last statement; none while unbound or if it had no rate then. */
    @Column(name = "stated_due")
    private BigDecimal statedDue;

    /**
     * The date the claim is due by: fixed when it is created, its date plus the terms of its
     * person's grade in force on its date; later changes to the terms or its date leave it as it is.
     */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    /** The number of the claim this correction corrects; none for a claim that is not a correction. */
    @Column(name = "corrects")
    private String corrects;

    /**
     * The claim's place in the list, newest (highest) first: taken from the store's sequence when
     * the claim is added and again when it is moved to another person.
     */
    @Column(name = "listed", insertable = false, updatable = false)
    private Long listed;

    /** The claim's status log: one entry per status it has had, oldest first. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_log", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "seq")
    @BatchSize(size = 100)
    private List<ClaimLogEntry> log = new ArrayList<>();

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

    /** Move the claim to another person. */
    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    public Long getListed() {
        return listed;
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

    public String getCorrects() {
        return corrects;
    }

    public void setCorrects(String corrects) {
        this.corrects = corrects;
    }

    /** Whether the claim is a correction: its amount is below zero. Its due has no floor and no late charge. */
    public boolean isCorrection() {
        return amount.signum() < 0;
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

    /** Bind the claim to a figure (stated), or unbind it so its figure is live again. */
    public void bind(boolean stated, BigDecimal statedDue) {
        this.stated = stated;
        this.statedDue = stated ? statedDue : null;
    }

    public List<ClaimLogEntry> getLog() {
        return List.copyOf(log);
    }

    /** Add a line to the claim's status log. */
    public void log(ClaimLogEntry entry) {
        log.add(entry);
    }
}
