package net.officefloor.hq.app.clients;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clients")
public class ClientController {

    public record ClientView(Long id, String name, String email) {
        static ClientView of(Client c) {
            return new ClientView(c.getId(), c.getName(), c.getEmail());
        }
    }

    public record NewClient(
            @NotBlank(message = "Enter the client's name.")
            @Size(max = 255, message = "The name must be at most 255 characters.")
            String name,
            @NotBlank(message = "Enter the client's email address.")
            @Email(regexp = PROPER_EMAIL,
                    message = "Enter a proper email address, like name@company.com.")
            @Size(max = 255, message = "The email must be at most 255 characters.")
            String email) {
    }

    /**
     * A proper email address: a mailbox, an @, and a domain with at least one dot whose parts are
     * not empty — so name@company.com, but not name@company (a typo) or name.company.com.
     */
    static final String PROPER_EMAIL = "\\s*[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+\\s*";

    private final ClientService clients;

    public ClientController(ClientService clients) {
        this.clients = clients;
    }

    @GetMapping
    public List<ClientView> list() {
        return clients.list().stream().map(ClientView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClientView add(@Valid @RequestBody NewClient body) {
        return ClientView.of(clients.add(body.name(), body.email()));
    }
}
