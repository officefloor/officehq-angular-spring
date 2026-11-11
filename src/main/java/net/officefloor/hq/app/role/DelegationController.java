package net.officefloor.hq.app.role;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The delegations of the steward role on a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/delegations")
@Validated
public class DelegationController {

    /** A delegation as shown, its dates as yyyy-mm-dd. */
    public record DelegationView(Long id, String user, String by, String from, String to) {

        static DelegationView of(Delegation d) {
            return new DelegationView(d.getId(), d.getUserName(), d.getDelegatedBy(), d.getFromDate().toString(),
                    d.getToDate().toString());
        }
    }

    /** The user delegated to, the from-date (blank: the sheet's today) and the to-date as yyyy-mm-dd. */
    public record DelegationRequest(@NotBlank @Size(max = 100) String user,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String from,
            @NotNull @Pattern(regexp = "\\s*\\d{4}-\\d{2}-\\d{2}\\s*") String to) {

        static LocalDate date(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return LocalDate.parse(value.trim());
            } catch (DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date: " + value.trim());
            }
        }
    }

    private final DelegationService service;

    public DelegationController(DelegationService service) {
        this.service = service;
    }

    /** The sheet's delegations in the order they were made. */
    @GetMapping
    public List<DelegationView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId.trim()).stream().map(DelegationView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DelegationView delegate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid DelegationRequest request) {
        return DelegationView.of(service.delegate(sheetId.trim(), user.trim(), request.user().trim(),
                DelegationRequest.date(request.from()), DelegationRequest.date(request.to())));
    }
}
