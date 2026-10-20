package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The claims people make on a sheet. */
@RestController
@RequestMapping("/api/sheets/{sheetId}")
public class ClaimController {

    /** The amount is a number with up to two decimals, negative for a correction, which names by number the
     * settled claim it corrects; no date means the sheet's today. */
    public record ClaimForm(
            @NotNull @Pattern(regexp = "-?\\d{1,10}(\\.\\d{1,2})?") String amount,
            LocalDate date,
            @Size(max = 1000) String note,
            @Size(max = 255) String corrects) {}

    /** The amount is shown with two decimals; a claim has no number until it is opened, and no fixed due
     * until it is settled; it shows who opened it, if anyone did, and whether it is bound to the figure it had
     * at its person's last statement (none when that was n/a); a correction shows the number it corrects; and the
     * date it is due by, fixed when it was added; and its status log, oldest first; and the date it is deferred
     * until, while it is deferred; and how many proofs it has; and, for a claim split off another, that claim's
     * number; and who has its editor open, and the sheet's today when they opened it, while they do. */
    public record ClaimView(Long id, Long personId, String amount, LocalDate date, String note, ClaimStatus status,
            String number, String due, String openedBy, boolean stated, String statedDue,
            String corrects, LocalDate dueBy, List<StatusLogView> statusLog, LocalDate deferredUntil,
            int proofs, String splitFrom, String lockedBy, LocalDate lockedOn) {

        public static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(),
                    claim.getAmount().setScale(2).toPlainString(), claim.getDate(), claim.getNote(), claim.getStatus(),
                    claim.getNumber(),
                    claim.getSettledDue() != null ? claim.getSettledDue().setScale(2).toPlainString() : null,
                    claim.getOpenedBy(), claim.isStated(),
                    claim.getStatedDue() != null ? claim.getStatedDue().setScale(2).toPlainString() : null,
                    claim.getCorrects(), claim.getDueBy(),
                    claim.getStatusLog().stream().map(StatusLogView::of).toList(), claim.getDeferredUntil(),
                    claim.getProofs(), claim.getSplitFrom(), claim.getLockedBy(), claim.getLockedOn());
        }
    }

    /** A line of a claim's status log: the status, who changed it, the sheet's today then, and the due then
     * with two decimals, or n/a. */
    public record StatusLogView(ClaimStatus status, String by, LocalDate on, String due) {

        public static StatusLogView of(StatusLogLine line) {
            return new StatusLogView(line.getStatus(), line.getBy(), line.getOn(), line.getDue());
        }
    }

    /** The claims to open, or settle, together, by id. */
    public record OpenAllForm(@NotNull @Size(max = 1000) List<@NotNull Long> ids) {}

    /** How many of the claims were opened and how many refused, and the opened claims as they are now. */
    public record OpenedAllView(int opened, int refused, List<ClaimView> claims) {}

    /** How many of the claims were settled (or sent PENDING) and how many refused, and those claims as they are
     * now. */
    public record SettledAllView(int settled, int refused, List<ClaimView> claims) {}

    /** Why a steward rejects a PENDING claim: one word or more, with no line break. */
    public record RejectForm(@NotBlank @Size(max = 255) @Pattern(regexp = "[^\\r\\n]*") String reason) {}

    /** The person a claim is moved to, by id. */
    public record ReassignForm(@NotNull Long personId) {}

    /** The date an OPEN claim is deferred until. */
    public record DeferForm(@NotNull LocalDate until) {}

    /** The new date of an OPEN or PENDING claim. */
    public record RedateForm(@NotNull LocalDate date) {}

    /** The amount split off an OPEN claim: a number with up to two decimals. */
    public record SplitForm(@NotNull @Pattern(regexp = "\\d{1,10}(\\.\\d{1,2})?") String amount) {}

    /** The claim split, with what it keeps, and the new claim split off it. */
    public record SplitView(ClaimView claim, ClaimView into) {}

    private final ClaimService service;

    public ClaimController(ClaimService service) {
        this.service = service;
    }

    @GetMapping("/claims")
    public List<ClaimView> list(@RequestHeader("X-User") String user, @PathVariable String sheetId) {
        return service.list(sheetId).stream().map(ClaimView::of).toList();
    }

    @PostMapping("/people/{personId}/claims")
    public ClaimView add(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @PathVariable Long personId, @Valid @RequestBody ClaimForm form) {
        return ClaimView.of(service.add(user, sheetId, personId, new BigDecimal(form.amount()), form.date(), form.note(),
                form.corrects()));
    }

    @PutMapping("/claims/{id}")
    public ClaimView update(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @PathVariable Long id, @Valid @RequestBody ClaimForm form) {
        return ClaimView.of(service.update(user, sheetId, id, new BigDecimal(form.amount()), form.date(), form.note(),
                form.corrects()));
    }

    @PostMapping("/claims/{id}/lock")
    public ClaimView lock(@RequestHeader("X-User") String user, @PathVariable String sheetId, @PathVariable Long id) {
        return ClaimView.of(service.lock(user, sheetId, id));
    }

    @PostMapping("/claims/{id}/unlock")
    public ClaimView unlock(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @PathVariable Long id) {
        return ClaimView.of(service.unlock(user, sheetId, id));
    }

    @PostMapping("/claims/{id}/open")
    public ClaimView open(@RequestHeader("X-User") String user, @PathVariable String sheetId, @PathVariable Long id) {
        return ClaimView.of(service.changeStatus(user, sheetId, id, ClaimStatus.OPEN));
    }

    @PostMapping("/claims/open")
    public OpenedAllView openAll(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @Valid @RequestBody OpenAllForm form) {
        ClaimService.OpenedAll result = service.openAll(user, sheetId, form.ids());
        return new OpenedAllView(result.opened(), result.refused(),
                result.claims().stream().map(ClaimView::of).toList());
    }

    @PostMapping("/claims/settle")
    public SettledAllView settleAll(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @Valid @RequestBody OpenAllForm form) {
        ClaimService.OpenedAll result = service.settleAll(user, sheetId, form.ids());
        return new SettledAllView(result.opened(), result.refused(),
                result.claims().stream().map(ClaimView::of).toList());
    }

    @PostMapping("/claims/{id}/settle")
    public ClaimView settle(@RequestHeader("X-User") String user, @PathVariable String sheetId, @PathVariable Long id) {
        return ClaimView.of(service.changeStatus(user, sheetId, id, ClaimStatus.SETTLED));
    }

    @PostMapping("/claims/{id}/approve")
    public ClaimView approve(@RequestHeader("X-User") String user, @PathVariable String sheetId, @PathVariable Long id) {
        return ClaimView.of(service.approve(user, sheetId, id));
    }

    @PostMapping("/claims/{id}/resettle")
    public ClaimView resettle(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @PathVariable Long id) {
        return ClaimView.of(service.resettle(user, sheetId, id));
    }

    @PostMapping("/claims/{id}/reject")
    public ClaimView reject(@RequestHeader("X-User") String user, @PathVariable String sheetId, @PathVariable Long id,
            @Valid @RequestBody RejectForm form) {
        return ClaimView.of(service.reject(user, sheetId, id, form.reason()));
    }

    @PostMapping("/claims/{id}/reassign")
    public ClaimView reassign(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @PathVariable Long id, @Valid @RequestBody ReassignForm form) {
        return ClaimView.of(service.reassign(user, sheetId, id, form.personId()));
    }

    @PostMapping("/claims/{id}/defer")
    public ClaimView defer(@RequestHeader("X-User") String user, @PathVariable String sheetId, @PathVariable Long id,
            @Valid @RequestBody DeferForm form) {
        return ClaimView.of(service.defer(user, sheetId, id, form.until()));
    }

    @PostMapping("/claims/{id}/redate")
    public ClaimView redate(@RequestHeader("X-User") String user, @PathVariable String sheetId, @PathVariable Long id,
            @Valid @RequestBody RedateForm form) {
        return ClaimView.of(service.redate(user, sheetId, id, form.date()));
    }

    @PostMapping("/claims/{id}/split")
    public SplitView split(@RequestHeader("X-User") String user, @PathVariable String sheetId, @PathVariable Long id,
            @Valid @RequestBody SplitForm form) {
        ClaimService.Split split = service.split(user, sheetId, id, new BigDecimal(form.amount()));
        return new SplitView(ClaimView.of(split.claim()), ClaimView.of(split.into()));
    }

    @PostMapping("/claims/{id}/proof")
    public ClaimView addProof(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @PathVariable Long id) {
        return ClaimView.of(service.addProof(user, sheetId, id));
    }

    @PostMapping("/claims/{id}/void")
    public ClaimView voidClaim(@RequestHeader("X-User") String user, @PathVariable String sheetId, @PathVariable Long id) {
        return ClaimView.of(service.changeStatus(user, sheetId, id, ClaimStatus.VOID));
    }
}
