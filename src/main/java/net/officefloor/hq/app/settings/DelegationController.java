package net.officefloor.hq.app.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The steward role delegated on a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/delegations")
@Validated
public class DelegationController {

    /** A delegation: the user given the steward role, the steward who delegated it, and its range, inclusive. */
    public record DelegationView(long id, String user, String delegator, LocalDate from, LocalDate to) {
        static DelegationView of(SheetDelegation d) {
            return new DelegationView(d.getId(), d.getUser(), d.getDelegator(), d.getFrom(), d.getTo());
        }
    }

    /** A delegation asked: the user, a from-date (YYYY-MM-DD; blank or null for the sheet's today) and a to-date. */
    public record DelegateRequest(@NotBlank @Size(max = 100) String user,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String from,
            @NotNull @Pattern(regexp = "\\s*\\d{4}-\\d{2}-\\d{2}\\s*") String to) {
        LocalDate fromValue() {
            return from == null || from.isBlank() ? null : LocalDate.parse(from.trim());
        }

        LocalDate toValue() {
            return LocalDate.parse(to.trim());
        }
    }

    private final DelegationService service;

    public DelegationController(DelegationService service) {
        this.service = service;
    }

    @GetMapping
    public List<DelegationView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.delegations(sheetId.trim()).stream().map(DelegationView::of).toList();
    }

    @PostMapping
    public List<DelegationView> delegate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid DelegateRequest request) {
        String sheet = sheetId.trim();
        service.delegate(sheet, user.trim(), request.user().trim(), request.fromValue(), request.toValue());
        return list(sheet);
    }
}
