package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.client.ClientRepository;
import net.officefloor.hq.app.client.ClientService;
import net.officefloor.hq.app.invoice.InvoiceRepository;
import net.officefloor.hq.app.invoice.InvoiceStatus;
import net.officefloor.hq.app.payment.PaymentRepository;
import net.officefloor.hq.app.project.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

    private final ClientRepository clients;
    private final ProjectRepository projects;
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final ClientService clientService;
    private final Clock clock;

    /** How many of the biggest debtors the dashboard lists. */
    static final int TOP_CLIENTS = 5;

    public DashboardService(ClientRepository clients, ProjectRepository projects, InvoiceRepository invoices,
            PaymentRepository payments, ClientService clientService, Clock clock) {
        this.clients = clients;
        this.projects = projects;
        this.invoices = invoices;
        this.payments = payments;
        this.clientService = clientService;
        this.clock = clock;
    }

    /**
     * Counts of clients and projects, and the total still owed (what is left to pay on sent invoices
     * that are not yet fully paid), plus how many of those sent invoices are past their due date, and
     * the top clients ranked by what they owe.
     */
    @Transactional(readOnly = true)
    public DashboardResponse summary() {
        List<InvoiceStatus> owing = List.of(InvoiceStatus.SENT, InvoiceStatus.PARTIAL);
        BigDecimal outstanding = invoices.sumAmountByStatusIn(owing)
                .subtract(payments.sumAmountByInvoiceStatusIn(owing))
                .setScale(2, RoundingMode.HALF_UP);
        long overdue = invoices.countByStatusInAndDueDateBefore(owing, LocalDate.now(clock));
        List<DashboardResponse.TopClient> top = clientService.topByOutstanding(TOP_CLIENTS).stream()
                .map(c -> new DashboardResponse.TopClient(c.id(), c.name(), c.outstanding()))
                .toList();
        return new DashboardResponse(clients.count(), projects.count(), outstanding, overdue, top);
    }
}
