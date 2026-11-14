package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.session.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class ClaimController {

    /** A claim as the screen shows it: the amount always with two decimals; a SETTLED claim's
     *  due as fixed when it was settled (null, so the due is live, when its grade had no rate then,
     *  and for other claims); who opened it (null while DRAFT); whether the person's last statement
     *  bound it, and the due it was bound to (null when unbound or n/a then). */
    public record ClaimView(long id, long personId, String amount, LocalDate date, String note,
            ClaimStatus status, Integer number, String settledDue, String openedBy, boolean stated,
            String statedDue) {
        static ClaimView of(Claim c) {
            return new ClaimView(c.getId(), c.getPersonId(), c.getAmount().setScale(2).toPlainString(),
                    c.getDate(), c.getNote(), c.getStatus(), c.getNumber(), money(c.getSettledDue()),
                    c.getOpenedBy(), c.isStated(), money(c.getStatedDue()));
        }

        private static String money(BigDecimal value) {
            return value == null ? null : value.setScale(2).toPlainString();
        }
    }

    /** A statement just taken: the person as they now show, and all their claims, newest first. */
    public record StatementView(PersonView person, List<ClaimView> claims) {
    }

    /** A claim's values as the form submits them: an amount of 0.00 or more with up to two
     *  decimals, an optional date (the sheet's today when absent) and an optional note. */
    public record ClaimForm(
            @NotNull @Pattern(regexp = "\\d{1,13}(\\.\\d{1,2})?") String amount,
            LocalDate date,
            @Size(max = 1000) String note) {
    }

    private final ClaimService service;

    public ClaimController(ClaimService service) {
        this.service = service;
    }

    @GetMapping("/claims")
    public List<ClaimView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId).stream().map(ClaimView::of).toList();
    }

    @PostMapping("/people/{personId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClaimForm body) {
        return ClaimView.of(service.add(sheetId, user.trim(), personId, new BigDecimal(body.amount()), body.date(), body.note()));
    }

    @PutMapping("/claims/{id}")
    public ClaimView update(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClaimForm body) {
        return ClaimView.of(service.update(sheetId, user.trim(), id, new BigDecimal(body.amount()), body.date(), body.note()));
    }

    @PostMapping("/claims/{id}/open")
    public ClaimView open(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.changeStatus(sheetId, user.trim(), id, ClaimStatus.OPEN));
    }

    @PostMapping("/claims/{id}/settle")
    public ClaimView settle(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.changeStatus(sheetId, user.trim(), id, ClaimStatus.SETTLED));
    }

    @PostMapping("/claims/{id}/void")
    public ClaimView voidClaim(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.changeStatus(sheetId, user.trim(), id, ClaimStatus.VOID));
    }

    @PostMapping("/people/{personId}/statement")
    public StatementView statement(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        ClaimService.Statement s = service.statement(sheetId, user.trim(), personId);
        return new StatementView(PersonView.of(s.person(), s.summary()),
                s.claims().stream().map(ClaimView::of).toList());
    }
}
