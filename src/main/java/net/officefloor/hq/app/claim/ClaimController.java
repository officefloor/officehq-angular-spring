package net.officefloor.hq.app.claim;

import org.springframework.format.annotation.DateTimeFormat;
import net.officefloor.hq.app.history.SheetHistory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Claims on a sheet. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class ClaimController {

    /**
     * A claim as the screen shows it; the amount is given with exactly two decimals, as is the due
     * fixed when it was settled (null while it is not settled, or if its grade had no rate then); openedBy
     * is who opened it (null while a draft); stated is whether its person's last statement binds it, to
     * statedDue (null if it had no figure then, when it stays n/a, or while PENDING, when no statement binds it); corrects is the number of the claim a correction corrects (null
     * on any other claim); deferredUntil is the day it was deferred until (null if never deferred); log is its status log, oldest first;
     * proofs is how many proofs have been given for it; splitFrom is the number of the claim it was split off (null
     * on any other claim); lockedBy is who has its editor open, locking it for other users (null when no one has);
     * lateEntry is whether it was added while its period was soft-closed; remindedOn is the last day a reminder was
     * written for it (null if never).
     */
    public record ClaimView(long id, long personId, String amount, LocalDate date, String note, ClaimStatus status,
            String number, String settledDue, String openedBy, boolean stated, String statedDue, String corrects,
            LocalDate dueBy, LocalDate deferredUntil, List<LogLine> log, int proofs, String splitFrom,
            String lockedBy, boolean lateEntry, LocalDate remindedOn) {

        public static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(), claim.getAmount().toPlainString(),
                    claim.getDate(), claim.getNote(), claim.getStatus(), claim.getNumber(),
                    claim.getSettledDue() == null ? null : claim.getSettledDue().toPlainString(),
                    claim.getOpenedBy(), claim.isStated(),
                    claim.getBoundDue() == null ? null : claim.getBoundDue().toPlainString(), claim.getCorrects(),
                    claim.getDueBy(), claim.getDeferredUntil(), claim.getLog().stream().map(LogLine::of).toList(),
                    claim.getProofs(), claim.getSplitFrom(), claim.getLockedBy(), claim.isLateEntry(),
                    claim.getRemindedOn());
        }
    }

    /** A line of a claim's status log: the status it took, by whom, on the sheet's today then, at the due it had
     *  then with exactly two decimals (null when it had none). */
    public record LogLine(ClaimStatus status, String by, LocalDate on, String due) {

        public static LogLine of(ClaimLogEntry entry) {
            return new LogLine(entry.getStatus(), entry.getBy(), entry.getOn(),
                    entry.getDue() == null ? null : entry.getDue().toPlainString());
        }
    }

    /** A claim's values as the form submits them; a missing date takes the sheet's today. A negative amount is a
     *  correction, which names in corrects the number of the claim it corrects. */
    public record ClaimForm(
            @NotNull @Digits(integer = 12, fraction = 2) BigDecimal amount,
            LocalDate date,
            @Size(max = 1000) String note,
            @Size(max = 120) String corrects) {
    }

    /** The status a claim is to move to. */
    public record StatusChange(@NotNull ClaimStatus status) {
    }

    /** Why a steward rejects a PENDING claim. */
    public record Rejection(@NotBlank @Size(max = 200) @Pattern(regexp = "\\S+( \\S+)*") String reason) {
    }

    /** The day an OPEN claim is to be deferred until. */
    public record Deferral(@NotNull LocalDate until) {
    }

    /** The new date of an OPEN or PENDING claim. */
    public record Redating(@NotNull LocalDate date) {
    }

    /** The person a claim is to move to. */
    public record Reassignment(@NotNull Long personId) {
    }

    /** The amount to split off an OPEN claim. */
    public record Split(@NotNull @Digits(integer = 12, fraction = 2) BigDecimal amount) {
    }

    /** What a split did: the claim split, keeping the rest of its amount, and the new claim split off it. */
    public record SplitView(ClaimView original, ClaimView split) {
    }

    /** The claims selected to be opened together. */
    public record BulkOpen(@NotNull @Size(max = 1000) List<@NotNull Long> ids) {
    }

    /** What a bulk open did: how many it opened and refused, and the claims it opened, in the order opened. */
    public record BulkOpenView(int opened, int refused, List<ClaimView> claims) {
    }

    /** The claims selected to be settled together. */
    public record BulkSettle(@NotNull @Size(max = 1000) List<@NotNull Long> ids) {
    }

    /** What a bulk settle did: how many it settled (a claim that went to PENDING counts) and refused, and the
     *  claims it settled, in the order settled. */
    public record BulkSettleView(int settled, int refused, List<ClaimView> claims) {
    }

    private final ClaimService claims;
    private final RoleService roles;
    private final SheetService sheets;
    private final SheetHistory history;

    public ClaimController(ClaimService claims, RoleService roles, SheetService sheets, SheetHistory history) {
        this.sheets = sheets;
        this.history = history;
        this.claims = claims;
        this.roles = roles;
    }

    @GetMapping("/claims")
    public List<ClaimView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(value = SheetHistory.AS_OF_HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        if (asOf != null) {
            return history.viewsAsOf(sheetId, asOf).claims();
        }
        return claims.list(sheetId).stream().map(ClaimView::of).toList();
    }

    @PostMapping("/people/{personId}/claims")
    public ClaimView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClaimForm request) {
        return ClaimView.of(claims.add(sheetId, user, personId, request.amount(), request.date(), request.note(),
                request.corrects()));
    }

    @PutMapping("/claims/{id}")
    public ClaimView update(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClaimForm request) {
        return ClaimView.of(claims.update(sheetId, user, id, request.amount(), request.date(), request.note(),
                request.corrects()));
    }

    /** Open the claim's editor, locking it for other users. */
    @PostMapping("/claims/{id}/lock")
    public ClaimView lock(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(claims.lock(sheetId, user, id));
    }

    /** Cancel the claim's editor, releasing the caller's lock. */
    @DeleteMapping("/claims/{id}/lock")
    public ClaimView unlock(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(claims.unlock(sheetId, user, id));
    }

    @PutMapping("/claims/{id}/status")
    public ClaimView changeStatus(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody StatusChange request) {
        return ClaimView.of(claims.changeStatus(sheetId, user, id, request.status()));
    }

    @PutMapping("/claims/{id}/approve")
    public ClaimView approve(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(claims.approve(sheetId, user, id));
    }

    @PutMapping("/claims/{id}/reject")
    public ClaimView reject(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody Rejection request) {
        return ClaimView.of(claims.reject(sheetId, user, id, request.reason()));
    }

    @PutMapping("/claims/{id}/resettle")
    public ClaimView resettle(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(claims.resettle(sheetId, user, id));
    }

    @PutMapping("/claims/{id}/defer")
    public ClaimView defer(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody Deferral request) {
        return ClaimView.of(claims.defer(sheetId, user, id, request.until()));
    }

    @PutMapping("/claims/{id}/date")
    public ClaimView redate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody Redating request) {
        return ClaimView.of(claims.redate(sheetId, user, id, request.date()));
    }

    @PostMapping("/claims/{id}/proofs")
    public ClaimView addProof(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(claims.addProof(sheetId, user, id));
    }

    @PostMapping("/claims/{id}/reminders")
    public ClaimView remind(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(claims.remind(sheetId, user, id));
    }

    @PutMapping("/claims/{id}/person")
    public ClaimView reassign(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody Reassignment request) {
        return ClaimView.of(claims.reassign(sheetId, user, id, request.personId()));
    }

    @PostMapping("/claims/{id}/split")
    public SplitView split(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody Split request) {
        ClaimService.Split split = claims.split(sheetId, user, id, request.amount());
        return new SplitView(ClaimView.of(split.original()), ClaimView.of(split.split()));
    }

    /**
     * Open each selected claim that can be opened, one at a time in list order (people in the order listed,
     * newest claim first within a person), so they are numbered in that order; each that cannot be opened, or is
     * not on the sheet, is refused and left as it is.
     */
    @PostMapping("/claims/bulk-open")
    public BulkOpenView bulkOpen(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody BulkOpen request) {
        if (roles.isViewer(sheetId, user) || sheets.isArchived(sheetId)) {
            // A viewer's bulk action, or one on an archived sheet, goes through with nothing done: every selected
            // claim is refused.
            return new BulkOpenView(0, (int) request.ids().stream().distinct().count(), List.of());
        }
        List<Long> ordered = claims.inListOrder(sheetId, request.ids());
        int refused = (int) request.ids().stream().distinct().count() - ordered.size();
        List<ClaimView> opened = new ArrayList<>();
        for (long id : ordered) {
            try {
                opened.add(ClaimView.of(claims.changeStatus(sheetId, user, id, ClaimStatus.OPEN)));
            } catch (ResponseStatusException refusal) {
                refused++;
            }
        }
        return new BulkOpenView(opened.size(), refused, opened);
    }

    /**
     * Settle each selected claim that can be settled, one at a time in list order (people in the order listed,
     * newest claim first within a person), by exactly the rules of settling one claim; a claim a clerk's limit
     * makes PENDING counts as settled. Each that cannot be settled, or is not on the sheet, is refused and left as
     * it is.
     */
    @PostMapping("/claims/bulk-settle")
    public BulkSettleView bulkSettle(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody BulkSettle request) {
        if (roles.isViewer(sheetId, user) || sheets.isArchived(sheetId)) {
            // A viewer's bulk action, or one on an archived sheet, goes through with nothing done: every selected
            // claim is refused.
            return new BulkSettleView(0, (int) request.ids().stream().distinct().count(), List.of());
        }
        List<Long> ordered = claims.inListOrder(sheetId, request.ids());
        int refused = (int) request.ids().stream().distinct().count() - ordered.size();
        List<ClaimView> settled = new ArrayList<>();
        for (long id : ordered) {
            try {
                settled.add(ClaimView.of(claims.changeStatus(sheetId, user, id, ClaimStatus.SETTLED)));
            } catch (ResponseStatusException refusal) {
                refused++;
            }
        }
        return new BulkSettleView(settled.size(), refused, settled);
    }
}
