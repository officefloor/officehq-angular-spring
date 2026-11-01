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

/**
 * A claim by a person on a sheet: an amount (two decimals), a date, a note and a status (DRAFT when
 * added), and a number and who opened it once opened. A negative amount makes it a correction, which
 * names the number of the SETTLED claim of its person that it corrects.
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

    /**
     * Given when the claim is opened: the sheet-wide number, or <period name>-<n> (X-<n> in no
     * period) while the sheet numbers by period; none while DRAFT, kept when VOID.
     */
    @Column(name = "claim_number")
    private String number;

    /** The period the claim was numbered within (empty: in no period); none unless numbered by period. */
    @Column(name = "number_period")
    private String numberPeriod;

    /** The claim's place in its period's numbering; none unless numbered by period. */
    @Column(name = "number_seq")
    private Integer numberSeq;

    /** The user who opened the claim; none while DRAFT. */
    @Column(name = "opened_by", length = 100)
    private String openedBy;

    /** The due fixed when the claim was settled; none while not settled, or settled with no rate. */
    @Column(name = "settled_due", precision = 14, scale = 2)
    private BigDecimal settledDue;

    /** Bound by its person's last statement, which it counted toward; its due is then {@link #statedDue}. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The due it had at its person's last statement; none while not stated, or stated with no due. */
    @Column(name = "stated_due", precision = 14, scale = 2)
    private BigDecimal statedDue;

    /** The number of the claim this correction corrects; none unless a correction. */
    @Column(name = "corrects")
    private String corrects;

    protected Claim() {}

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, String note) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.date = date;
        this.note = note;
    }

    public Long getId() { return id; }

    /** A correction: a negative amount. Its due has no floor and no late charge. */
    public boolean isCorrection() { return amount.signum() < 0; }

    public String getCorrects() { return corrects; }

    public void setCorrects(String corrects) { this.corrects = corrects; }

    public String getSheetId() { return sheetId; }

    public Long getPersonId() { return personId; }

    public BigDecimal getAmount() { return amount; }

    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public LocalDate getDate() { return date; }

    public void setDate(LocalDate date) { this.date = date; }

    public String getNote() { return note; }

    public void setNote(String note) { this.note = note; }

    public ClaimStatus getStatus() { return status; }

    public void setStatus(ClaimStatus status) { this.status = status; }

    public String getNumber() { return number; }

    /** Number the claim sheet-wide. */
    public void setNumber(long number) { this.number = String.valueOf(number); }

    /** Number the claim within a period (empty name: in no period): <period name>-<n>, or X-<n>. */
    public void setNumberWithin(String period, int seq) {
        this.numberPeriod = period;
        this.numberSeq = seq;
        this.number = (period.isEmpty() ? "X" : period) + "-" + seq;
    }

    public String getOpenedBy() { return openedBy; }

    public void setOpenedBy(String openedBy) { this.openedBy = openedBy; }

    public BigDecimal getSettledDue() { return settledDue; }

    public void setSettledDue(BigDecimal settledDue) { this.settledDue = settledDue; }

    public boolean isStated() { return stated; }

    public BigDecimal getStatedDue() { return statedDue; }

    /**
     * Whether its due is now bound by its person's last statement: stated and not PENDING. A PENDING
     * claim shows its live due, and is bound again if rejected.
     */
    public boolean isBound() { return stated && status != ClaimStatus.PENDING; }

    /** Bind the claim to a due (null: it has none), as its person's statement does. */
    public void state(BigDecimal due) {
        this.stated = true;
        this.statedDue = due;
    }

    /** Leave the claim unbound, its due live again. */
    public void unstate() {
        this.stated = false;
        this.statedDue = null;
    }
}
