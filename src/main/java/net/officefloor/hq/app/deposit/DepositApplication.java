package net.officefloor.hq.app.deposit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Part of a client's held deposits put toward several of their invoices on a given day. */
@Entity
@Table(name = "deposit_application")
public class DepositApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "applied_on", nullable = false)
    private LocalDate date;

    protected DepositApplication() {
    }

    public DepositApplication(Long clientId, BigDecimal amount, LocalDate date) {
        this.clientId = clientId;
        this.amount = amount.setScale(2);
        this.date = date;
    }

    public Long getId() {
        return id;
    }

    public Long getClientId() {
        return clientId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getDate() {
        return date;
    }
}
