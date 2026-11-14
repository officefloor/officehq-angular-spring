package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.person.PersonService;
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
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class ClaimController {

    /** A claim as the screen shows it: the amount always with two decimals; a SETTLED claim's
     *  due as fixed when it was settled (null, so the due is live, when its grade had no rate then,
     *  and for other claims); who opened it (null while DRAFT); whether the person's last statement
     *  bound it, and the due it was bound to (null when unbound or n/a then); the number of the
     *  claim a correction corrects (null for other claims); its status log, oldest first. */
    public record ClaimView(long id, long personId, String amount, LocalDate date, String note,
            ClaimStatus status, String number, String settledDue, String openedBy, boolean stated,
            String statedDue, String corrects, long listed, LocalDate dueBy, List<LogLineView> log) {
        static ClaimView of(Claim c, List<ClaimLog> log) {
            return new ClaimView(c.getId(), c.getPersonId(), c.getAmount().setScale(2).toPlainString(),
                    c.getDate(), c.getNote(), c.getStatus(), c.getNumber(), money(c.getSettledDue()),
                    c.getOpenedBy(), c.isStated(), money(c.getStatedDue()), c.getCorrects(), c.getListed(), c.getDueBy(),
                    log.stream().map(LogLineView::of).toList());
        }

        private static String money(BigDecimal value) {
            return value == null ? null : value.setScale(2).toPlainString();
        }
    }

    /** A line of a claim's status log: the status, who moved the claim there, the sheet's today
     *  then, and the claim's due at that moment with two decimals (null: n/a). */
    public record LogLineView(ClaimStatus status, String by, LocalDate on, String due) {
        static LogLineView of(ClaimLog line) {
            return new LogLineView(line.getStatus(), line.getBy(), line.getOn(), ClaimView.money(line.getDue()));
        }
    }

    /** A statement just taken: the person as they now show, and all their claims, newest first. */
    public record StatementView(PersonView person, List<ClaimView> claims) {
    }

    /** A claim's values as the form submits them: an amount with up to two decimals (negative
     *  for a correction), an optional date (the sheet's today when absent), an optional note, and
     *  the number of the claim a correction corrects. */
    public record ClaimForm(
            @NotNull @Pattern(regexp = "-?\\d{1,13}(\\.\\d{1,2})?") String amount,
            LocalDate date,
            @Size(max = 1000) String note,
            @Size(max = 120) @Pattern(regexp = "\\s*\\S+\\s*") String corrects) {
    }

    /** The reason a steward gives for rejecting a PENDING claim. */
    public record RejectForm(@NotBlank @Size(max = 500) String reason) {
    }

    /** The person a claim is reassigned to. */
    public record ReassignForm(@NotNull Long personId) {
    }

    /** The claims selected for a bulk action, by id. */
    public record BulkForm(@NotNull @Size(max = 10000) List<@NotNull Long> ids) {
    }

    /** A bulk open's result: how many of the selected claims were opened and how many refused. */
    public record BulkOpenView(int opened, int refused) {
    }

    /** A bulk settle's result: how many of the selected claims were settled (a claim sent to
     *  PENDING counts) and how many refused. */
    public record BulkSettleView(int settled, int refused) {
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    /** The claim as the screen shows it, with its status log. */
    ClaimView view(Claim c) {
        return ClaimView.of(c, service.logOf(c.getId()));
    }

    private final ClaimService service;
    private final PersonService people;

    public ClaimController(ClaimService service, PersonService people) {
        this.service = service;
        this.people = people;
    }

    @GetMapping("/claims")
    public List<ClaimView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        Map<Long, List<ClaimLog>> logs = service.logs(sheetId);
        return service.list(sheetId).stream().map(c -> ClaimView.of(c, logs.getOrDefault(c.getId(), List.of()))).toList();
    }

    @PostMapping("/people/{personId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClaimForm body) {
        return view(service.add(sheetId, user.trim(), personId, new BigDecimal(body.amount()), body.date(), body.note(), trimmed(body.corrects())));
    }

    @PutMapping("/claims/{id}")
    public ClaimView update(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClaimForm body) {
        return view(service.update(sheetId, user.trim(), id, new BigDecimal(body.amount()), body.date(), body.note(), trimmed(body.corrects())));
    }

    @PostMapping("/claims/{id}/open")
    public ClaimView open(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(service.changeStatus(sheetId, user.trim(), id, ClaimStatus.OPEN));
    }

    /** Open each selected claim in list order (people in the order added, newest claim first),
     *  each on its own, numbered in that order; a claim that cannot be opened is left as it is. */
    @PostMapping("/claims/bulk-open")
    public BulkOpenView bulkOpen(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody BulkForm body) {
        List<Long> ordered = service.inListOrder(sheetId, body.ids());
        int opened = 0;
        for (long id : ordered) {
            try {
                service.changeStatus(sheetId, user.trim(), id, ClaimStatus.OPEN);
                opened++;
            } catch (ResponseStatusException e) {
                // refused: left as it is
            }
        }
        return new BulkOpenView(opened, (int) body.ids().stream().distinct().count() - opened);
    }

    @PostMapping("/claims/{id}/settle")
    public ClaimView settle(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(service.changeStatus(sheetId, user.trim(), id, ClaimStatus.SETTLED));
    }

    /** Settle each selected claim in list order (people in the order added, newest claim first),
     *  each on its own by the rules of settling one; a claim sent to PENDING counts as settled, and
     *  a claim that cannot be settled is left as it is. */
    @PostMapping("/claims/bulk-settle")
    public BulkSettleView bulkSettle(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody BulkForm body) {
        List<Long> ordered = service.inListOrder(sheetId, body.ids());
        int settled = 0;
        for (long id : ordered) {
            try {
                service.changeStatus(sheetId, user.trim(), id, ClaimStatus.SETTLED);
                settled++;
            } catch (ResponseStatusException e) {
                // refused: left as it is
            }
        }
        return new BulkSettleView(settled, (int) body.ids().stream().distinct().count() - settled);
    }

    @PostMapping("/claims/{id}/approve")
    public ClaimView approve(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(service.approve(sheetId, user.trim(), id));
    }

    @PostMapping("/claims/{id}/resettle")
    public ClaimView resettle(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(service.resettle(sheetId, user.trim(), id));
    }

    @PostMapping("/claims/{id}/reject")
    public ClaimView reject(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RejectForm body) {
        return view(service.reject(sheetId, user.trim(), id, body.reason().trim()));
    }

    @PostMapping("/claims/{id}/reassign")
    public ClaimView reassign(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ReassignForm body) {
        return view(service.reassign(sheetId, user.trim(), id, body.personId()));
    }

    @PostMapping("/claims/{id}/void")
    public ClaimView voidClaim(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(service.changeStatus(sheetId, user.trim(), id, ClaimStatus.VOID));
    }

    @PostMapping("/people/{personId}/statement")
    public StatementView statement(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        ClaimService.Statement s = service.statement(sheetId, user.trim(), personId);
        return new StatementView(PersonView.of(s.person(), people.today(sheetId), s.summary(), people.patronIds(s.person().getId())),
                s.claims().stream().map(this::view).toList());
    }
}
