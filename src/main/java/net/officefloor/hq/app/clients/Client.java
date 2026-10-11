package net.officefloor.hq.app.clients;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** A client invoices are raised for. */
@Entity
@Table(name = "client")
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String email;

    /** Payment terms in days; null means the usual end of the following month. */
    private Integer termsDays;

    /** The standard percentage discount new invoices for this client start with; 0 is none. */
    @Column(name = "discount_pct")
    private BigDecimal discountPct = BigDecimal.ZERO;

    protected Client() {
    }

    public Client(String name, String email, Integer termsDays, BigDecimal discountPct) {
        this.name = name;
        this.email = email;
        this.termsDays = termsDays;
        this.discountPct = discountPct == null ? BigDecimal.ZERO : discountPct;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public Integer getTermsDays() {
        return termsDays;
    }

    public BigDecimal getDiscountPct() {
        return discountPct;
    }
}
