package net.officefloor.hq.app.deposit;

import jakarta.validation.Valid;
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

    public ClientDepositController(DepositService service) {
        this.service = service;
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
}
