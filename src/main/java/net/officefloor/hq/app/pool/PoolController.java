package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.crew.CrewController;
import net.officefloor.hq.app.history.AsOfGuard;
import net.officefloor.hq.app.history.HistoryService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.session.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sheets/{sheetId}/pools")
@Validated
public class PoolController {

    /** A member as the screen shows it: the person's id, or the member pool's name, and the share
     *  with two decimals, and the membership's from- and to-date (yyyy-mm-dd, null when not given). */
    public record MemberView(Long personId, String pool, String share, String from, String to) {
        static MemberView of(PoolMember m, Map<Long, String> poolNames) {
            return new MemberView(m.getPersonId(),
                    m.getMemberPoolId() == null ? null : poolNames.get(m.getMemberPoolId()),
                    m.getShare().setScale(2).toPlainString(),
                    m.getFromDate() == null ? null : m.getFromDate().toString(),
                    m.getToDate() == null ? null : m.getToDate().toString());
        }
    }

    /** A pool as the screen shows it: its name and its members in the order they were added. */
    public record PoolView(String name, List<MemberView> members) {
        static PoolView of(PoolService.PoolWithMembers p, Map<Long, String> poolNames) {
            return new PoolView(p.pool().getName(),
                    p.members().stream().map(m -> MemberView.of(m, poolNames)).toList());
        }
    }

    /** A hold as the screen reads it: its from- and to-date (yyyy-mm-dd, the to-date null when open). */
    public record HoldView(String from, String to) {
    }

    /** A person as a period's cuts need them: their id, the date they are retired from (null when
     *  not retired) and their holds. */
    public record PersonThen(long id, String retiredFrom, List<HoldView> holds) {
        static PersonThen of(long id, Person p) {
            return new PersonThen(id, p.getRetiredFrom() == null ? null : p.getRetiredFrom().toString(),
                    p.getHolds().stream()
                            .map(h -> new HoldView(h.from().toString(), h.to() == null ? null : h.to().toString()))
                            .toList());
        }
    }

    /** A period whose to-date has come, or that is closed, with what its cuts are worked from as
     *  they stood at the end of its to-date (or when it was closed): the period's name and the day
     *  the cuts are taken on (its to-date, or the sheet's today when it was closed), the pot then (two
     *  decimals, null when none), the pools and crews then and the people then. */
    public record PeriodCutsView(String period, String to, String pot, List<PoolView> pools,
            List<CrewController.CrewView> crews, List<PersonThen> people) {
        static PeriodCutsView of(PoolService.PeriodThen p) {
            HistoryService.AsOf then = p.then();
            Map<Long, String> poolNames = then.pools().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().name()));
            List<PoolView> pools = then.pools().entrySet().stream()
                    .map(e -> new PoolView(e.getValue().name(), then.poolMembers().values().stream()
                            .filter(m -> m.poolId() == e.getKey())
                            .map(m -> new MemberView(m.personId(),
                                    m.memberPoolId() == null ? null : poolNames.get(m.memberPoolId()),
                                    m.share().setScale(2).toPlainString(),
                                    m.from() == null ? null : m.from().toString(),
                                    m.to() == null ? null : m.to().toString()))
                            .toList()))
                    .toList();
            List<CrewController.CrewView> crews = then.crews().entrySet().stream()
                    .map(e -> new CrewController.CrewView(e.getValue().name(), e.getValue().leadId(),
                            then.crewMembers().values().stream().filter(m -> m.crewId() == e.getKey())
                                    .map(HistoryService.CrewMemberState::personId).toList()))
                    .toList();
            return new PeriodCutsView(p.period().getName(), p.day().toString(),
                    then.sheet().getPot() == null ? null : then.sheet().getPot().setScale(2).toPlainString(),
                    pools, crews,
                    then.people().entrySet().stream().map(e -> PersonThen.of(e.getKey(), e.getValue())).toList());
        }
    }

    /** A pool as the form submits it: a name (not blank). */
    public record PoolForm(@NotBlank @Size(max = 200) String name) {
    }

    /** A member as the pool's row submits it: the pool, a person or another pool of the sheet
     *  (exactly one), and a share percent with up to two decimals, more than 0.00 and at most
     *  100.00, and an optional from- and to-date (yyyy-mm-dd, blank for none). */
    public record MemberForm(
            @NotBlank @Size(max = 200) String pool,
            Long personId,
            @Size(max = 200) String memberPool,
            @NotNull @Pattern(regexp = "\\s*\\d{1,3}(\\.\\d{1,2})?\\s*") String share,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String from,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String to) {
    }

    private final PoolService service;

    public PoolController(PoolService service) {
        this.service = service;
    }

    @GetMapping
    public List<PoolView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        List<PoolService.PoolWithMembers> all = service.list(sheetId);
        Map<Long, String> poolNames = all.stream()
                .collect(Collectors.toMap(p -> p.pool().getId(), p -> p.pool().getName()));
        return all.stream().map(p -> PoolView.of(p, poolNames)).toList();
    }

    /** Each period whose to-date is on or before the sheet's today (or the as-of date), or that is
     *  closed, by from-date, with the pools, crews, pot and people as they stood at the end of its
     *  to-date (as they stood when it was closed, for a closed one). */
    @GetMapping("/history")
    public List<PeriodCutsView> history(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.history(sheetId, asOf).stream().map(PeriodCutsView::of).toList();
    }

    /** Add a pool; resolves to the sheet's pools with it. */
    @PostMapping
    public List<PoolView> add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PoolForm body) {
        service.add(sheetId, user.trim(), body.name().trim());
        return list(sheetId, user);
    }

    /** Add a member to a pool; resolves to the sheet's pools with it. */
    @PostMapping("/members")
    public List<PoolView> addMember(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody MemberForm body) {
        boolean isPool = body.memberPool() != null && !body.memberPool().isBlank();
        if (isPool == (body.personId() != null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a person or a pool");
        }
        BigDecimal share = share(body.share());
        LocalDate from = date(body.from());
        LocalDate to = date(body.to());
        if (from != null && to != null && to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "The to-date is before the from-date");
        }
        if (isPool) {
            service.addPoolMember(sheetId, user.trim(), body.pool().trim(), body.memberPool().trim(), share, from, to);
        } else {
            service.addMember(sheetId, user.trim(), body.pool().trim(), body.personId(), share, from, to);
        }
        return list(sheetId, user);
    }

    /** A date, null when blank; refused (400) when not a real date. */
    private static LocalDate date(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date");
        }
    }

    /** The share, refused (400) unless more than 0.00 and at most 100.00. */
    private static BigDecimal share(String text) {
        BigDecimal share = new BigDecimal(text.trim()).setScale(2);
        if (share.signum() <= 0 || share.compareTo(new BigDecimal("100.00")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A share is more than 0.00 and at most 100.00");
        }
        return share;
    }
}
