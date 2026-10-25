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

    @Column(name = "note", nullable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /** The sheet's number for the claim, given when it is opened and kept once void; none while a draft. */
    @Column(name = "claim_number")
    private Integer number;

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
    private Integer corrects;

    protected Claim() {
    }

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, String note, Integer corrects) {
        this.sheetId = sheetId;
        this.personId = personId;
        set(amount, date, note, corrects);
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

    public String getNote() {
        return note;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public void setStatus(ClaimStatus status) {
        this.status = status;
    }

    public Integer getNumber() {
        return number;
    }

    public void setNumber(Integer number) {
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

    public Integer getCorrects() {
        return corrects;
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
    public void set(BigDecimal amount, LocalDate date, String note, Integer corrects) {
        this.amount = amount.setScale(2);
        this.date = date;
        this.note = note;
        this.corrects = isCorrection() ? corrects : null;
    }
}
