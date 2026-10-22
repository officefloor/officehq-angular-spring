package net.officefloor.hq.app.role;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.SignedIn;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/delegations")
@Validated
public class DelegationController {

    /** What the screen is told about a delegation: who acts as a steward, for which steward, and when. */
    public record DelegationView(long id, String user, String steward, LocalDate from, LocalDate to) {

        static DelegationView of(Delegation d) {
            return new DelegationView(d.getId(), d.getUserName(), d.getSteward(), d.getFromDate(), d.getToDate());
        }
    }

    /** A delegation as entered on the form: a user name and an inclusive from and to date. */
    public record DelegationForm(
            @NotBlank @Size(max = 200) String user,
            @NotNull LocalDate from,
            @NotNull LocalDate to) {
    }

    private final DelegationService service;

    public DelegationController(DelegationService service) {
        this.service = service;
    }

    /** The delegations made on the sheet, in the order they were made. */
    @GetMapping
    public List<DelegationView> list(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        return service.list(sheetId).stream().map(DelegationView::of).toList();
    }

    /** Delegate the steward role to a user name for a range of the sheet's today; a steward only. */
    @PostMapping
    public DelegationView delegate(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid DelegationForm form) {
        return DelegationView.of(service.delegate(sheetId, SignedIn.name(user), form.user().trim(), form.from(), form.to()));
    }
}
