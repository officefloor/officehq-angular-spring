package net.officefloor.hq.app.refund;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Unused credit paid back to a client. */
@RestController
@RequestMapping("/api/clients/{clientId}/refunds")
public class ClientRefundController {

    private final RefundService service;

    public ClientRefundController(RefundService service) {
        this.service = service;
    }

    @GetMapping
    public List<RefundResponse> list(@PathVariable Long clientId) {
        return service.list(clientId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RefundResponse refund(@PathVariable Long clientId, @Valid @RequestBody RefundRequest request) {
        return service.refund(clientId, request);
    }
}
