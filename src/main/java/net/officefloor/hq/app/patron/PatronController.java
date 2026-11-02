package net.officefloor.hq.app.patron;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@Validated
public class PatronController {

    /** A patron named: the person who named them and the person named, by id. */
    public record PatronView(long id, long personId, long patronId) {
        static PatronView of(Patron patron) {
            return new PatronView(patron.getId(), patron.getPersonId(), patron.getPatronId());
        }
    }

    /** The patron form: the id of the person to name as patron. */
    public record PatronForm(@NotNull Long patronId) {
    }

    private final PatronService service;

    public PatronController(PatronService service) {
        this.service = service;
    }

    @GetMapping("/api/sheets/{sheetId}/patrons")
    public List<PatronView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(PatronView::of).toList();
    }

    @PostMapping("/api/sheets/{sheetId}/people/{personId}/patrons")
    @ResponseStatus(HttpStatus.CREATED)
    public PatronView add(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PatronForm form) {
        return PatronView.of(service.add(sheetId.trim(), user.trim(), personId, form.patronId()));
    }
}
