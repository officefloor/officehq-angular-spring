package net.officefloor.hq.app.credit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The credit a client has to spend: their unused deposits and credit notes. */
@RestController
public class ClientCreditController {

    private final ClientCreditService service;

    public ClientCreditController(ClientCreditService service) {
        this.service = service;
    }

    @GetMapping("/api/clients/{clientId}/credit")
    public ClientCreditResponse available(@PathVariable Long clientId) {
        return service.available(clientId);
    }
}
