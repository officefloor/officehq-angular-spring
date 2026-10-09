package net.officefloor.hq.app.invoice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import net.officefloor.hq.app.project.Project;

/** An invoice raised against a project. */
@Entity
@Table(name = "invoice")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    protected Invoice() {
    }

    public Invoice(Project project, BigDecimal amount) {
        this.project = project;
        this.amount = amount;
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
