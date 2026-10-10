package net.officefloor.hq.app.instalment;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The instalments an invoice is split into, earliest due first. */
@RestController
@RequestMapping("/api/projects/{projectId}/invoices/{invoiceId}/instalments")
public class InvoiceInstalmentController {

    private final InstalmentService service;

    public InvoiceInstalmentController(InstalmentService service) {
        this.service = service;
    }

    @GetMapping
    public List<InstalmentResponse> list(@PathVariable Long projectId, @PathVariable Long invoiceId) {
        return service.list(projectId, invoiceId);
    }

    /** The next instalment due on the invoice, or no content when nothing more is due on its plan. */
    @GetMapping("/next")
    public ResponseEntity<NextInstalmentResponse> next(@PathVariable Long projectId, @PathVariable Long invoiceId) {
        return service.next(projectId, invoiceId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** The interest charged for each day an instalment is paid late. */
    @GetMapping("/interest")
    public InstalmentInterestResponse interest(@PathVariable Long projectId, @PathVariable Long invoiceId) {
        return service.interest(projectId, invoiceId);
    }

    @PutMapping("/interest")
    public InstalmentInterestResponse applyInterest(@PathVariable Long projectId, @PathVariable Long invoiceId,
            @Valid @RequestBody InstalmentInterestRequest request) {
        return service.applyInterest(projectId, invoiceId, request);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InstalmentResponse schedule(@PathVariable Long projectId, @PathVariable Long invoiceId,
            @Valid @RequestBody InstalmentRequest request) {
        return service.schedule(projectId, invoiceId, request);
    }

    @DeleteMapping("/{instalmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long projectId, @PathVariable Long invoiceId, @PathVariable Long instalmentId) {
        service.remove(projectId, invoiceId, instalmentId);
    }
}
