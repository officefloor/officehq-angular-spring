package net.officefloor.hq.app.role;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The delegations of the steward role on a sheet. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/delegations")
public class DelegationController {

    public record DelegationView(long id, String delegator, String user, String from, String to) {

        static DelegationView of(Delegation d) {
            return new DelegationView(d.getId(), d.getDelegator(), d.getUserName(), d.getFrom().toString(),
                    d.getTo().toString());
        }
    }

    /** The user delegated to and the dates, inclusive; a from-date left out takes the sheet's today, a to-date
     *  left out the from-date. */
    public record DelegationRequest(@NotBlank @Size(max = 200) String user, LocalDate from, LocalDate to) {
    }

    private final RoleService service;

    public DelegationController(RoleService service) {
        this.service = service;
    }

    /** The sheet's delegations in the order they were made. */
    @GetMapping
    public List<DelegationView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return service.delegations(sheetId.trim()).stream().map(DelegationView::of).toList();
    }

    /** Delegate the steward role to a user for a from-date to a to-date. */
    @PostMapping
    public DelegationView delegate(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid DelegationRequest request) {
        return DelegationView.of(service.delegate(sheetId.trim(), user.trim(), request.user().trim(),
                request.from(), request.to()));
    }

    /** A delegation that cannot be made is refused with the reason, for the screen to show. */
    @ExceptionHandler(DelegationRefusedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, String> refused(DelegationRefusedException e) {
        return Map.of("message", e.getMessage());
    }
}
