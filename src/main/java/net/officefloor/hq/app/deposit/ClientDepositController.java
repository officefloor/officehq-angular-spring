package net.officefloor.hq.app.deposit;

import jakarta.validation.Valid;
import net.officefloor.hq.app.payment.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Deposits a client pays up front, before any invoice. */
@RestController
@RequestMapping("/api/clients/{clientId}/deposits")
public class ClientDepositController {

    private final DepositService service;
    private final PaymentService payments;

    public ClientDepositController(DepositService service, PaymentService payments) {
        this.service = service;
        this.payments = payments;
    }

    @GetMapping
    public ClientDepositsResponse list(@PathVariable Long clientId) {
        return service.list(clientId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DepositResponse record(@PathVariable Long clientId, @Valid @RequestBody DepositRequest request) {
        return service.record(clientId, request);
    }

    /** Puts part of the client's held deposits toward their invoices, split across them. */
    @PostMapping("/applications")
    @ResponseStatus(HttpStatus.CREATED)
    public DepositApplicationResponse apply(@PathVariable Long clientId,
            @Valid @RequestBody DepositApplicationRequest request) {
        return payments.applyDeposits(clientId, request);
    }
}
