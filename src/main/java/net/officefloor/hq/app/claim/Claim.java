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

/** A claim made by a person on a sheet. */
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

    /** The date the claim is due by, fixed when it is added from the terms of its person's grade in
     *  force on its date; later changes to the terms or to the claim's date leave it as it is. */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    @Column(name = "note", nullable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /** The claim's number, given when it is opened and kept once void; none while a draft. It is the
     *  sheet-wide 1, 2, ..., or while period numbering is in force <period name>-<n> or X-<n>. */
    @Column(name = "claim_number")
    private String number;

    /** What was due on the claim when it was settled; none while unsettled, or when no rate applied then. */
    @Column(name = "settled_due", precision = 12, scale = 2)
    private BigDecimal settledDue;

    /** Who opened the claim; none while a draft, or when voided without being opened. */
    @Column(name = "opened_by")
    private String openedBy;

    /** Whether the person's last statement bound the claim to a figure; it then shows stated due. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The figure the last statement bound the claim to; none when unbound, or when it was n/a then. */
    @Column(name = "stated_due", precision = 12, scale = 2)
    private BigDecimal statedDue;

    /** The number of the settled claim of the same person a correction (a negative amount) corrects; none otherwise. */
    @Column(name = "corrects")
    private String corrects;

    /** The claim's place in the sheet's list, which is newest first: the sheet's next place when it is
     *  added, and again when it is moved to another person. */
    @Column(name = "listed", nullable = false)
    private long listed;

    /** The claim's status log, oldest first: a line for each status it has had. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_log", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "position")
    private List<ClaimLogLine> log = new ArrayList<>();

    protected Claim() {
    }

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, String note, String corrects,
            int terms) {
        this.sheetId = sheetId;
        this.personId = personId;
        set(amount, date, note, corrects);
        this.dueBy = date.plusDays(terms);
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

    public void setListed(long listed) {
        this.listed = listed;
    }

    /** Move the claim to another person, as their newest claim at the place given: it keeps its number
     *  and date, and loses any figure a statement bound it to, its due live again. */
    public void moveTo(Long personId, long listed) {
        this.personId = personId;
        this.listed = listed;
        unbind();
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

    public void setStatus(ClaimStatus status) {
        this.status = status;
    }

    public List<ClaimLogLine> getLog() {
        return List.copyOf(log);
    }

    /** Log the claim's status now: who gave it, the sheet's today and its due (null: n/a) then. */
    public void logStatus(String by, LocalDate on, BigDecimal due) {
        log.add(new ClaimLogLine(status, by, on, due));
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

    public String getCorrects() {
        return corrects;
    }

    /** Whether the claim is a correction: its amount is negative. */
    public boolean isCorrection() {
        return amount.signum() < 0;
    }

    public boolean isStated() {
        return stated;
    }

    /** Whether the claim is bound to its stated figure now: stated, and not PENDING, as no statement
     *  binds a PENDING claim (its binding holds again should it be rejected). */
    public boolean isBound() {
        return stated && status != ClaimStatus.PENDING;
    }

    public BigDecimal getStatedDue() {
        return statedDue;
    }

    /** Bind the claim to a figure (null: n/a) as a statement takes it. */
    public void bind(BigDecimal figure) {
        this.stated = true;
        this.statedDue = figure;
    }

    /** Leave the claim unbound, its due live again. */
    public void unbind() {
        this.stated = false;
        this.statedDue = null;
    }

    /** Change the claim's amount, date, note and the number it corrects; the amount is kept to two
     *  decimals, and only a correction keeps the number it corrects. */
    public void set(BigDecimal amount, LocalDate date, String note, String corrects) {
        this.amount = amount.setScale(2);
        this.date = date;
        this.note = note;
        this.corrects = isCorrection() ? corrects : null;
    }
}
