package net.officefloor.hq.app.role;

import org.springframework.format.annotation.DateTimeFormat;
import net.officefloor.hq.app.history.SheetHistory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

/** The steward role's delegations on a sheet. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/delegations")
public class DelegationController {

    /** A delegation as the screen shows it. */
    public record DelegationView(long id, String user, String by, LocalDate from, LocalDate to) {

        public static DelegationView of(Delegation d) {
            return new DelegationView(d.getId(), d.getUserName(), d.getDelegatedBy(), d.getFromDate(), d.getToDate());
        }
    }

    /** A delegation as the form submits it; a blank date is the sheet's today. */
    public record DelegationForm(@NotBlank @Size(max = 100) @Pattern(regexp = "\\S(.*\\S)?") String user,
            LocalDate from, LocalDate to) {
    }

    private final DelegationService delegations;
    private final SheetHistory history;

    public DelegationController(DelegationService delegations, SheetHistory history) {
        this.history = history;
        this.delegations = delegations;
    }

    @GetMapping
    public List<DelegationView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(value = SheetHistory.AS_OF_HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        if (asOf != null) {
            return history.viewsAsOf(sheetId, asOf).delegations();
        }
        return delegations.list(sheetId).stream().map(DelegationView::of).toList();
    }

    @PostMapping
    public DelegationView delegate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody DelegationForm request) {
        return DelegationView.of(delegations.delegate(sheetId, user, request.user(), request.from(), request.to()));
    }
}
