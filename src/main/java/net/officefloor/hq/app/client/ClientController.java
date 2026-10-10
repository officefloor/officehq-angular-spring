package net.officefloor.hq.app.client;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    /** Downloads the client's contact details as a CSV file. */
    @GetMapping(value = "/{id}/export", produces = "text/csv")
    public ResponseEntity<String> export(@PathVariable Long id) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("client-" + id + ".csv").build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(service.exportContactDetails(id));
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

    @PutMapping("/{id}/credit-limit")
    public ClientResponse changeCreditLimit(@PathVariable Long id, @Valid @RequestBody ClientCreditLimitRequest request) {
        return service.changeCreditLimit(id, request.creditLimit());
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
