package net.officefloor.hq.app.recurring;

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

/** An invoice on a project that repeats on a schedule for a fixed amount, next falling on a given day. */
@Entity
@Table(name = "recurring_invoice")
public class RecurringInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecurringFrequency frequency;

    @Column(name = "next_date", nullable = false)
    private LocalDate nextDate;

    protected RecurringInvoice() {
    }

    public RecurringInvoice(Long projectId, BigDecimal amount, RecurringFrequency frequency, LocalDate nextDate) {
        this.projectId = projectId;
        this.amount = amount.setScale(2);
        this.frequency = frequency;
        this.nextDate = nextDate;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public RecurringFrequency getFrequency() {
        return frequency;
    }

    public LocalDate getNextDate() {
        return nextDate;
    }

    /** Moves the schedule on to the date the invoice after this one falls on. */
    public void advance() {
        this.nextDate = switch (frequency) {
            case MONTHLY -> nextDate.plusMonths(1);
        };
    }
}
