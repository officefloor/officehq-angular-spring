package net.officefloor.hq.app.payment;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A lump payment from a client, split across several of their invoices. */
@RestController
@RequestMapping("/api/clients/{clientId}/payments")
public class ClientPaymentController {

    private final PaymentService service;

    public ClientPaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClientPaymentResponse record(@PathVariable Long clientId, @Valid @RequestBody ClientPaymentRequest request) {
        return service.recordForClient(clientId, request);
    }
}
