package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import net.officefloor.hq.app.client.ClientRepository;
import net.officefloor.hq.app.invoice.InvoiceRepository;
import net.officefloor.hq.app.invoice.InvoiceStatus;
import net.officefloor.hq.app.project.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

    private final ClientRepository clients;
    private final ProjectRepository projects;
    private final InvoiceRepository invoices;

    public DashboardService(ClientRepository clients, ProjectRepository projects, InvoiceRepository invoices) {
        this.clients = clients;
        this.projects = projects;
        this.invoices = invoices;
    }

    /** Counts of clients and projects, and the total still owed (sum of sent, unpaid invoices). */
    @Transactional(readOnly = true)
    public DashboardResponse summary() {
        BigDecimal outstanding = invoices.sumAmountByStatus(InvoiceStatus.SENT).setScale(2, RoundingMode.HALF_UP);
        return new DashboardResponse(clients.count(), projects.count(), outstanding);
    }
}
