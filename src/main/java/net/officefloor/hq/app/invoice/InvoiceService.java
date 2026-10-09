package net.officefloor.hq.app.invoice;

import java.util.List;
import net.officefloor.hq.app.project.Project;
import net.officefloor.hq.app.project.ProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InvoiceService {

    private final InvoiceRepository invoices;
    private final ProjectRepository projects;

    public InvoiceService(InvoiceRepository invoices, ProjectRepository projects) {
        this.invoices = invoices;
        this.projects = projects;
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> listForProject(Long projectId) {
        if (!projects.existsById(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown project");
        }
        return invoices.findByProjectIdOrderById(projectId).stream().map(InvoiceResponse::from).toList();
    }

    @Transactional
    public InvoiceResponse create(Long projectId, InvoiceRequest request) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown project"));
        Invoice saved = invoices.save(new Invoice(project, request.amount()));
        return InvoiceResponse.from(saved);
    }
}
