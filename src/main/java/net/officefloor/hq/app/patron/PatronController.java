package net.officefloor.hq.app.patron;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import net.officefloor.hq.app.history.SheetHistory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The patrons people name on a sheet. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class PatronController {

    public record PatronView(long id, long personId, long patronId) {

        public static PatronView of(Patron patron) {
            return new PatronView(patron.getId(), patron.getPersonId(), patron.getPatronId());
        }
    }

    /** The person to name as a patron. */
    public record PatronForm(@NotNull Long patronId) {
    }

    private final PatronService patrons;
    private final SheetHistory history;

    public PatronController(PatronService patrons, SheetHistory history) {
        this.history = history;
        this.patrons = patrons;
    }

    @GetMapping("/patrons")
    public List<PatronView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(value = SheetHistory.AS_OF_HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        if (asOf != null) {
            return history.viewsAsOf(sheetId, asOf).patrons();
        }
        return patrons.list(sheetId).stream().map(PatronView::of).toList();
    }

    @PostMapping("/people/{id}/patrons")
    public PatronView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PatronForm request) {
        return PatronView.of(patrons.add(sheetId, user, id, request.patronId()));
    }
}
