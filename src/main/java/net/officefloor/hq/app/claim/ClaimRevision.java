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
import java.util.Objects;

/** A revision of a claim in its sheet's history: what its figures were worked out from after a
 *  change, recorded on the sheet's today then. */
@Entity
@Table(name = "claim_revision")
public class ClaimRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    /** The sheet's today when the revision was recorded. */
    @Column(name = "recorded_on", nullable = false)
    private LocalDate recordedOn;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "claim_date", nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status;

    @Column(name = "settled_due", precision = 14, scale = 2)
    private BigDecimal settledDue;

    @Column(name = "stated", nullable = false)
    private boolean stated;

    @Column(name = "stated_due", precision = 14, scale = 2)
    private BigDecimal statedDue;

    @Column(name = "stated_na", nullable = false)
    private boolean statedNa;

    @Column(name = "corrected_date")
    private LocalDate correctedDate;

    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    @Column(name = "deferred_until")
    private LocalDate deferredUntil;

    @Column(name = "waived", nullable = false)
    private boolean waived;

    @Column(name = "held", nullable = false)
    private boolean held;

    /** The claim's place in the order claims are added (see Claim#getAdded). */
    @Column(name = "added", nullable = false)
    private long added;

    /** Whether the claim had left the sheet by then (see {@link #gone(LocalDate)}). */
    @Column(name = "gone", nullable = false)
    private boolean gone;

    protected ClaimRevision() {
    }

    /** A revision of the claim as it is now, recorded on the day. */
    public ClaimRevision(Claim claim, LocalDate recordedOn) {
        this.sheetId = claim.getSheetId();
        this.claimId = claim.getId();
        this.recordedOn = recordedOn;
        this.personId = claim.getPersonId();
        this.amount = claim.getAmount();
        this.date = claim.getDate();
        this.status = claim.getStatus();
        this.settledDue = claim.getSettledDue();
        this.stated = claim.isStated();
        this.statedDue = claim.getStatedDue();
        this.statedNa = claim.isStatedNa();
        this.correctedDate = claim.getCorrectedDate();
        this.dueBy = claim.getDueBy();
        this.deferredUntil = claim.getDeferredUntil();
        this.waived = claim.isWaived();
        this.held = claim.isHeld();
        this.added = claim.getAdded();
    }

    /** The revision recording, on the day, that the claim has left the sheet: as this one has it
     *  otherwise. */
    public ClaimRevision gone(LocalDate recordedOn) {
        ClaimRevision left = new ClaimRevision();
        left.sheetId = sheetId;
        left.claimId = claimId;
        left.recordedOn = recordedOn;
        left.personId = personId;
        left.amount = amount;
        left.date = date;
        left.status = status;
        left.settledDue = settledDue;
        left.stated = stated;
        left.statedDue = statedDue;
        left.statedNa = statedNa;
        left.correctedDate = correctedDate;
        left.dueBy = dueBy;
        left.deferredUntil = deferredUntil;
        left.waived = waived;
        left.held = held;
        left.added = added;
        left.gone = true;
        return left;
    }

    /** Whether the claim had left the sheet by this revision. */
    public boolean isGone() {
        return gone;
    }

    /** Whether the claim is now as this revision has it. */
    public boolean matches(Claim claim) {
        return Objects.equals(personId, claim.getPersonId()) && same(amount, claim.getAmount())
                && Objects.equals(date, claim.getDate()) && status == claim.getStatus()
                && same(settledDue, claim.getSettledDue()) && stated == claim.isStated()
                && same(statedDue, claim.getStatedDue()) && statedNa == claim.isStatedNa()
                && Objects.equals(correctedDate, claim.getCorrectedDate()) && Objects.equals(dueBy, claim.getDueBy())
                && Objects.equals(deferredUntil, claim.getDeferredUntil()) && waived == claim.isWaived()
                && held == claim.isHeld();
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    /** The claim as this revision has it, detached: for working out figures only, never saved. */
    public Claim toClaim() {
        return Claim.revised(this);
    }

    public Long getId() {
        return id;
    }

    public long getAdded() {
        return added;
    }

    public Long getClaimId() {
        return claimId;
    }

    public LocalDate getRecordedOn() {
        return recordedOn;
    }

    Long getPersonId() {
        return personId;
    }

    String getSheetId() {
        return sheetId;
    }

    BigDecimal getAmount() {
        return amount;
    }

    LocalDate getDate() {
        return date;
    }

    ClaimStatus getStatus() {
        return status;
    }

    BigDecimal getSettledDue() {
        return settledDue;
    }

    boolean isStated() {
        return stated;
    }

    BigDecimal getStatedDue() {
        return statedDue;
    }

    boolean isStatedNa() {
        return statedNa;
    }

    LocalDate getCorrectedDate() {
        return correctedDate;
    }

    LocalDate getDueBy() {
        return dueBy;
    }

    LocalDate getDeferredUntil() {
        return deferredUntil;
    }

    boolean isWaived() {
        return waived;
    }

    boolean isHeld() {
        return held;
    }
}
