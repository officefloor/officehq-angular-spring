package net.officefloor.hq.app.invoices;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One milestone of an invoice billed by milestones: a description and the percentage (over 0, up to two
 * decimals) of what the client pays that it is worth. Once the invoice is sent it is owed only from the
 * day it is reached, and falls due the client's payment terms after that day (see {@link Invoice#milestoneDueDate}).
 */
@Entity
@Table(name = "invoice_milestone")
public class Milestone {

    /** Where a milestone is: not reached yet, or reached and paid off, part paid, overdue or due. */
    public enum Status {
        PENDING, PAID, PART_PAID, OVERDUE, DUE
    }

    /**
     * A milestone's amount, what has been paid off it, the day it falls due (null while pending), the late
     * interest it has been charged and what of it is still unpaid, and its status on a day.
     */
    public record Progress(Milestone milestone, BigDecimal amount, BigDecimal paid, LocalDate dueDate,
            BigDecimal lateInterest, BigDecimal interestUnpaid, Status status) {

        /** All still owed on a reached milestone: what is unpaid of its amount and of its late interest. */
        public BigDecimal owed() {
            return status == Status.PENDING ? BigDecimal.ZERO : amount.subtract(paid).add(interestUnpaid);
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String description;

    private BigDecimal pct;

    /** The day the milestone was reached; null while it is pending. */
    @Column(name = "reached_on")
    private LocalDate reachedOn;

    protected Milestone() {
    }

    public Milestone(String description, BigDecimal pct) {
        this.description = description;
        this.pct = pct;
    }

    public Long getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPct() {
        return pct;
    }

    public LocalDate getReachedOn() {
        return reachedOn;
    }

    public boolean isReached() {
        return reachedOn != null;
    }

    /** Marks the milestone reached on the day. */
    public void reach(LocalDate on) {
        this.reachedOn = on;
    }
}
