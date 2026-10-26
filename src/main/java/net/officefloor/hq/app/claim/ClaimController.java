package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class ClaimController {

    /**
     * A claim as the screen shows it; the amount always carries two decimals. The settled due is the figure fixed
     * when it was settled, or null while its due is live. Opened-by is the user who opened it, or null. Stated is
     * whether its person's last statement binds it, to the stated due (null for n/a). Corrects is the number of the
     * claim a correction corrects, or null. Rounds-on is the date its rounding follows: its own, or for a correction
     * the date of the claim it corrects. Log is its status log, oldest first.
     */
    public record ClaimView(Long id, Long personId, String amount, LocalDate date, String note, ClaimStatus status,
            String number, String settledDue, String openedBy, boolean stated, String statedDue, String corrects,
            LocalDate roundsOn, LocalDate dueBy, List<LogLineView> log) {
        public static ClaimView of(Claim c) {
            return new ClaimView(c.getId(), c.getPersonId(), c.getAmount().toPlainString(), c.getDate(), c.getNote(),
                    c.getStatus(), c.getNumber(), c.getSettledDue() == null ? null : c.getSettledDue().toPlainString(),
                    c.getOpenedBy(), c.isStated(), c.getStatedDue() == null ? null : c.getStatedDue().toPlainString(),
                    c.getCorrects(), c.getRoundsOn(), c.getDueBy(), c.getLog().stream().map(LogLineView::of).toList());
        }
    }

    /** A line of a claim's status log: the status, who made the change, the sheet's today then, and the due then. */
    public record LogLineView(ClaimStatus status, String by, LocalDate on, String due) {
        public static LogLineView of(ClaimLogEntry e) {
            return new LogLineView(e.getStatus(), e.getBy(), e.getOn(),
                    e.getDue() == null ? null : e.getDue().toPlainString());
        }
    }

    /**
     * A claim's fields as the form submits them; a missing date takes the sheet's today. A negative amount is a
     * correction, and corrects names the number of the settled claim it corrects.
     */
    public record ClaimForm(@NotNull @Digits(integer = 12, fraction = 2) BigDecimal amount, LocalDate date,
            @Size(max = 500) String note, @Size(max = 140) String corrects) {
    }

    /** The claims a bulk action is to change, by id. */
    public record BulkForm(@NotNull @Size(max = 1000) Set<@NotNull Long> ids) {
    }

    /** The claims a bulk open opened, in the order it numbered them, and how many it refused. */
    public record BulkOpenView(List<ClaimView> opened, int refused) {
    }

    private final ClaimService service;
    private final ClaimBulkService bulk;

    public ClaimController(ClaimService service, ClaimBulkService bulk) {
        this.service = service;
        this.bulk = bulk;
    }

    /** All the claims on the sheet, newest first. */
    @GetMapping("/claims")
    public List<ClaimView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.list(sheetId).stream().map(ClaimView::of).toList();
    }

    @PostMapping("/people/{personId}/claims")
    public ClaimView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long personId, @Valid @RequestBody ClaimForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.add(user, sheetId, personId, form.amount(), form.date(), form.note(),
                form.corrects()));
    }

    @PutMapping("/claims/{id}")
    public ClaimView update(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody ClaimForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.update(user, sheetId, id, form.amount(), form.date(), form.note(),
                form.corrects()));
    }

    @PostMapping("/claims/{id}/open")
    public ClaimView open(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.changeStatus(user, sheetId, id, ClaimStatus.OPEN));
    }

    @PostMapping("/claims/bulk-open")
    public BulkOpenView bulkOpen(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody BulkForm form, @SessionAttribute(SessionController.USER) String user) {
        ClaimBulkService.Result result = bulk.open(user, sheetId, form.ids());
        return new BulkOpenView(result.changed().stream().map(ClaimView::of).toList(), result.refused());
    }

    @PostMapping("/claims/{id}/settle")
    public ClaimView settle(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.changeStatus(user, sheetId, id, ClaimStatus.SETTLED));
    }

    /** A steward's reason for rejecting a pending claim. */
    public record RejectForm(@NotBlank @Size(max = 200) String reason) {
    }

    @PostMapping("/claims/{id}/approve")
    public ClaimView approve(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.approve(user, sheetId, id));
    }

    @PostMapping("/claims/{id}/reject")
    public ClaimView reject(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody RejectForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.reject(user, sheetId, id, form.reason()));
    }

    /** The person a claim is to move to, by id. */
    public record ReassignForm(@NotNull Long personId) {
    }

    @PostMapping("/claims/{id}/reassign")
    public ClaimView reassign(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody ReassignForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.reassign(user, sheetId, id, form.personId()));
    }

    @PostMapping("/claims/{id}/void")
    public ClaimView voidClaim(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.changeStatus(user, sheetId, id, ClaimStatus.VOID));
    }
}
