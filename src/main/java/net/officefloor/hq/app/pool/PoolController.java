package net.officefloor.hq.app.pool;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.AsOf;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.person.PersonController;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
import tools.jackson.databind.json.JsonMapper;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/pools")
public class PoolController {

    /** A pool member as the screen shows it: a person, or another pool (memberPoolId); the share is a
     *  string with two decimals; from and to bound the membership, both included (null: no bound). */
    public record PoolMemberView(Long personId, Long memberPoolId, String share, LocalDate from, LocalDate to) {
        static PoolMemberView of(PoolMember member) {
            return new PoolMemberView(member.getPersonId(), member.getMemberPoolId(),
                    member.getShare().toPlainString(), member.getFrom(), member.getTo());
        }
    }

    /** A pool as the screen shows it, with its members in the order they were added. A crew shows as a
     *  pool too (crew true), its id the crew's negated so it is told apart from the sheet's pools; its
     *  members are its lead's and its members' and none is added through the pool. */
    public record PoolView(Long id, String name, List<PoolMemberView> members, boolean crew) {
        static PoolView of(PoolService.PoolWithMembers pool) {
            return new PoolView(pool.pool().getId(), pool.pool().getName(),
                    pool.members().stream().map(PoolMemberView::of).toList(), false);
        }

        static PoolView of(PoolService.CrewPool pool) {
            List<PoolMemberView> members = new ArrayList<>();
            for (int i = 0; i < pool.personIds().size(); i++) {
                members.add(new PoolMemberView(pool.personIds().get(i), null, pool.shares().get(i).toPlainString(),
                        null, null));
            }
            return new PoolView(-pool.crew().getId(), pool.crew().getName(), members, true);
        }
    }

    /** A person as a pool saw them at the end of a day: whether a retirement date was set, and the
     *  holds put on them by then. */
    public record PersonThenView(Long id, String name, boolean retired, List<PersonController.HoldView> holds) {
        static PersonThenView of(PoolHistoryService.PersonThen then) {
            return new PersonThenView(then.person().getId(), then.person().getName(), then.retired(),
                    then.holds().stream().map(PersonController.HoldView::of).toList());
        }
    }

    /** A period that has ended and, at the end of its to-date (day), the pot (two decimals; null for
     *  none), the pools and crews as pools, and the people, as they stood. */
    public record PeriodPoolsView(String period, LocalDate day, String pot, List<PoolView> pools,
            List<PersonThenView> people) {
        static PeriodPoolsView of(PoolHistoryService.PeriodPools then, JsonMapper json) {
            return then.frozen() != null ? json.readValue(then.frozen(), PeriodPoolsView.class) : of(then);
        }

        static PeriodPoolsView of(PoolHistoryService.PeriodPools then) {
            List<PoolView> pools = new ArrayList<>(then.pools().stream().map(PoolView::of).toList());
            then.crews().stream().map(PoolView::of).forEach(pools::add);
            return new PeriodPoolsView(then.period(), then.day(),
                    then.pot() != null ? then.pot().toPlainString() : null, pools,
                    then.people().stream().map(PersonThenView::of).toList());
        }
    }

    /** A pool: its name, trimmed as it is added. */
    public record PoolRequest(@NotBlank @Size(max = 255) String name) {
    }

    /** A member: a person of the sheet, or another pool of the sheet (memberPoolId), and its share
     *  percent, with an optional from-date and to-date. */
    public record PoolMemberRequest(Long personId, Long memberPoolId, @NotNull BigDecimal share, LocalDate from,
            LocalDate to) {
    }

    private final PoolService service;
    private final PoolHistoryService pastPools;
    private final JsonMapper json;
    private final RollupService rollups;

    public PoolController(PoolService service, PoolHistoryService pastPools, JsonMapper json,
            RollupService rollups) {
        this.service = service;
        this.pastPools = pastPools;
        this.json = json;
        this.rollups = rollups;
    }

    /** The sheet's pools, in the order they were added, then its crews as pools, in the order they were added. */
    @GetMapping
    public List<PoolView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId) {
        List<PoolView> list = new ArrayList<>(service.list(sheetId.trim()).stream().map(PoolView::of).toList());
        service.crewPools(sheetId.trim()).stream().map(PoolView::of).forEach(list::add);
        return list;
    }

    /** Each period whose to-date is on or before the sheet's today, the earliest first, with the pools
     *  as they stood at the end of it (a closed or soft-closed one's as they stood when it was closed); while the screen is set to an as-of date, that date stands in for
     *  the sheet's today. */
    @GetMapping("/by-period")
    public List<PeriodPoolsView> byPeriod(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(value = AsOf.HEADER, required = false) String asOfHeader) {
        return pastPools.byPeriod(sheetId.trim(), AsOf.parse(asOfHeader)).stream()
                .map(then -> PeriodPoolsView.of(then, json)).toList();
    }

    /** Add a pool to the sheet. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PoolView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated PoolRequest request) {
        return PoolView.of(service.add(sheetId.trim(), SignedInUser.decode(user), request.name()));
    }

    /** Add a member to one of the sheet's pools; the figures its cuts move (a cut counts against the cap)
     *  are followed in the audit. */
    @PostMapping("/{poolId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public PoolView addMember(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long poolId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated PoolMemberRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return PoolView.of(rollups.track(sheet, by, () -> service.addMember(sheet, by, poolId, request.personId(),
                request.memberPoolId(), request.share(), request.from(), request.to())));
    }

    /** A blank or used name, a person already in the pool, a pool that may not be a member, a bad
     *  share, or a to-date before the from-date, is refused. */
    @ExceptionHandler({PoolRefusedException.class, DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public void refused() {
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }
}
