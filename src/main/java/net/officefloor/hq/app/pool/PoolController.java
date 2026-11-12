package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.crew.Crew;
import net.officefloor.hq.app.person.PersonService;
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

/** The pools of a sheet and their members. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/pools")
@Validated
public class PoolController {

    /**
     * A member as shown: the person's id, or the member pool's id, their share (two decimals) and the membership's
     * from-date and to-date (null when not given).
     */
    public record PoolMemberView(Long personId, Long poolId, String share, LocalDate from, LocalDate to) {

        static PoolMemberView of(PoolMember m) {
            return new PoolMemberView(m.getPersonId(), m.getMemberPoolId(), m.getShare().toPlainString(),
                    m.getFromDate(), m.getToDate());
        }
    }

    /**
     * A pool as shown, with its members in the order they were added. A crew is shown as a pool too ({@code crew}
     * true, its id the crew's id negated): its lead (if it still has one) and then its members, at the shares patrons get.
     */
    public record PoolView(Long id, String name, List<PoolMemberView> members, boolean crew) {

        static PoolView of(Pool p) {
            return new PoolView(p.getId(), p.getName(), p.getMembers().stream().map(PoolMemberView::of).toList(),
                    false);
        }

        static PoolView of(Crew c) {
            List<Long> people = new ArrayList<>();
            if (c.getLeadId() != null) {
                people.add(c.getLeadId());
            }
            c.getMembers().forEach(m -> people.add(m.getPersonId()));
            List<BigDecimal> shares = PersonService.patronShares(people.size());
            List<PoolMemberView> members = new ArrayList<>();
            for (int i = 0; i < people.size(); i++) {
                members.add(new PoolMemberView(people.get(i), null, shares.get(i).toPlainString(), null, null));
            }
            return new PoolView(-c.getId(), c.getName(), members, true);
        }
    }

    /** The pool's name: surrounding spaces are removed, and it must not then be blank. */
    public record PoolRequest(@NotNull @NotBlank @Size(max = 255) String name) {
    }

    /**
     * A member to add: the person, or else another pool, and their share, more than 0.00 and at most 100.00, with at
     * most two decimals, with an optional from-date and to-date, the to-date not before the from-date.
     */
    public record PoolMemberRequest(Long personId, Long poolId,
            @NotNull @Pattern(regexp = "\\s*\\d{1,3}(\\.\\d{1,2})?\\s*") String share, LocalDate from,
            LocalDate to) {

        @AssertTrue(message = "the to-date is not before the from-date")
        public boolean isDatesInOrder() {
            return from == null || to == null || !to.isBefore(from);
        }

        @AssertTrue(message = "a member is a person or a pool")
        public boolean isOneMember() {
            return (personId == null) != (poolId == null);
        }
    }

    private final PoolService service;

    public PoolController(PoolService service) {
        this.service = service;
    }

    /** The sheet's pools, in the order they were added, and then its crews as pools, in the order they were added. */
    @GetMapping
    public List<PoolView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        List<PoolView> views = new ArrayList<>(service.list(sheetId.trim()).stream().map(PoolView::of).toList());
        service.crews(sheetId.trim()).forEach(c -> views.add(PoolView.of(c)));
        return views;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PoolView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PoolRequest request) {
        return PoolView.of(service.add(sheetId.trim(), user.trim(), request.name().trim()));
    }

    @PostMapping("/{poolId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public PoolView addMember(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long poolId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PoolMemberRequest request) {
        BigDecimal share = new BigDecimal(request.share().trim());
        if (request.poolId() != null) {
            return PoolView.of(service.addMemberPool(sheetId.trim(), user.trim(), poolId, request.poolId(), share,
                    request.from(), request.to()));
        }
        return PoolView.of(service.addMember(sheetId.trim(), user.trim(), poolId, request.personId(), share,
                request.from(), request.to()));
    }
}
