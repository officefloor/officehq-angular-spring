package net.officefloor.hq.app.invoice;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.project.Project;

/**
 * An invoice raised against a project. It is built from line items, and its stored amount is always
 * the sum of what each line charges.
 */
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
    private BigDecimal amount = BigDecimal.ZERO.setScale(2);

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    @Column(name = "issued_date", nullable = false)
    private LocalDate issuedDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<InvoiceLineItem> lineItems = new ArrayList<>();

    protected Invoice() {
    }

    public Invoice(Project project, LocalDate issuedDate, LocalDate dueDate) {
        this.project = project;
        this.issuedDate = issuedDate;
        this.dueDate = dueDate;
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

    public InvoiceStatus getStatus() {
        return status;
    }

    public LocalDate getIssuedDate() {
        return issuedDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public List<InvoiceLineItem> getLineItems() {
        return List.copyOf(lineItems);
    }

    /** Adds a line to this invoice and reworks its amount to include what the line charges. */
    public InvoiceLineItem addLineItem(String description, BigDecimal qty, BigDecimal unitPrice) {
        InvoiceLineItem item = new InvoiceLineItem(this, description, qty, unitPrice);
        lineItems.add(item);
        recalculateAmount();
        return item;
    }

    /** Changes one of this invoice's lines and reworks its amount to match. */
    public void updateLineItem(InvoiceLineItem item, String description, BigDecimal qty, BigDecimal unitPrice) {
        item.update(description, qty, unitPrice);
        recalculateAmount();
    }

    /** Removes a line from this invoice and reworks its amount to no longer include what the line charged. */
    public void removeLineItem(InvoiceLineItem item) {
        lineItems.remove(item);
        recalculateAmount();
    }

    /** The line on this invoice with the given id, if there is one. */
    public Optional<InvoiceLineItem> findLineItem(Long lineItemId) {
        return lineItems.stream().filter(l -> l.getId().equals(lineItemId)).findFirst();
    }

    private void recalculateAmount() {
        this.amount = lineItems.stream().map(InvoiceLineItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2);
    }

    /** Marks this invoice as sent to the client. */
    public void markSent() {
        this.status = InvoiceStatus.SENT;
    }

    /** Marks this invoice as paid. */
    public void markPaid() {
        this.status = InvoiceStatus.PAID;
    }
}
