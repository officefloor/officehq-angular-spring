package net.officefloor.hq.app.role;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
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

@RestController
@RequestMapping("/api/sheets/{sheetId}/delegations")
@Validated
public class DelegationController {

    /** What the screen shows of a delegation: who gave the steward role to whom, from and to. */
    public record DelegationView(long id, String user, String by, LocalDate from, LocalDate to) {
        static DelegationView of(Delegation d) {
            return new DelegationView(d.getId(), d.getUserName(), d.getDelegatedBy(), d.getFromDate(), d.getToDate());
        }
    }

    /** The delegation form: the required user name, the from-date (the sheet's today when none is
     *  given) and the to-date (the from-date when none is given). */
    public record DelegationForm(@NotBlank @Size(max = 100) String user, LocalDate from, LocalDate to) {
    }

    private final RoleService service;

    public DelegationController(RoleService service) {
        this.service = service;
    }

    @GetMapping
    public List<DelegationView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.delegations(sheetId.trim()).stream().map(DelegationView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DelegationView delegate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody DelegationForm form) {
        return DelegationView.of(service.delegate(sheetId.trim(), user.trim(), form.user().trim(), form.from(), form.to()));
    }
}
