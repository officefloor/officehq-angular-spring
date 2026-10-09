package net.officefloor.hq.app.client;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clients")
public class ClientController {

    private final ClientService service;

    public ClientController(ClientService service) {
        this.service = service;
    }

    @GetMapping
    public List<ClientResponse> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(includeArchived);
    }

    @GetMapping("/{id}")
    public ClientResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping("/{id}/summary")
    public ClientSummaryResponse summary(@PathVariable Long id) {
        return service.summary(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClientResponse create(@Valid @RequestBody ClientRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public ClientResponse update(@PathVariable Long id, @Valid @RequestBody ClientRequest request) {
        return service.update(id, request);
    }

    @PutMapping("/{id}/currency")
    public ClientResponse changeCurrency(@PathVariable Long id, @Valid @RequestBody ClientCurrencyRequest request) {
        return service.changeCurrency(id, request.currency());
    }

    @PostMapping("/{id}/archive")
    public ClientResponse archive(@PathVariable Long id) {
        return service.archive(id);
    }

    @PostMapping("/{id}/restore")
    public ClientResponse restore(@PathVariable Long id) {
        return service.restore(id);
    }

    @PostMapping("/{id}/merge")
    public ClientResponse merge(@PathVariable Long id, @Valid @RequestBody ClientMergeRequest request) {
        return service.merge(id, request.targetId());
    }
}
