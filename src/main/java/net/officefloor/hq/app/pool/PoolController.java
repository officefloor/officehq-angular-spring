package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.crew.Crew;
import net.officefloor.hq.app.crew.CrewService;
import net.officefloor.hq.app.history.AsOf;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
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

/** A sheet's pools and their members. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/pools")
public class PoolController {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    /** A member, a person or another pool (poolId set, never retired): a retired person (a retirement date set,
     *  whatever the date) counts its share toward the total but is left out of the cuts (0.00, its share spread
     *  over the others; none when every member is retired). Its cut of the pool's pot (two decimals; 0.00 for a person
     *  on hold on the sheet's today, whose share is spread over the others); none when the pool is unbalanced or
     *  has no pot. A member whose dates (from, to: optional) do not include the sheet's today is left out
     *  entirely: it counts 0.00 toward the total and has no cut. Its cut at the end of each period whose to-date is
     *  on or before the sheet's today, by from-date (cuts). */
    public record PoolMemberView(Long personId, Long poolId, String name, String share, boolean retired, String cut,
            LocalDate from, LocalDate to, boolean active, List<PeriodCut> cuts) {

        PoolMemberView withCuts(List<PeriodCut> cuts) {
            return new PoolMemberView(personId, poolId, name, share, retired, cut, from, to, active, cuts);
        }

        /** Whether it is the same member as another of the same pool: the same person, or the same pool. */
        boolean same(PoolMemberView other) {
            return Objects.equals(personId, other.personId) && Objects.equals(poolId, other.poolId);
        }
    }

    /** A member's cut as it stood at the end of a period (its to-date as the sheet's today, with the pool, its
     *  members, the pot and the holds as they were then); none when there was none. */
    public record PeriodCut(String period, String cut) {
    }

    /** A pool, with its total share and whether that is exactly 100.00. A crew is a pool too (crew set): its id is
     *  the crew's id negated, its members its lead and then its members, at equal shares. */
    public record PoolView(long id, String name, List<PoolMemberView> members, String totalShare, boolean balanced,
            boolean crew) {

        /** The sheet's pools as views, in their order. A pool that is a member of another has as its pot its cut
         *  in the pool above (none when that is unbalanced or has none); one that is a member of none has the
         *  sheet's pot. A person on hold on the sheet's today gets no cut from any pool. The crews follow, each a
         *  pool of the sheet's pot. */
        static List<PoolView> of(List<Pool> pools, List<Crew> crews, Map<Long, Person> people, BigDecimal sheetPot,
                LocalDate today) {
            Map<Long, Pool> byId = new HashMap<>();
            Map<Long, Pool> parentOf = new HashMap<>();
            for (Pool pool : pools) {
                byId.put(pool.getId(), pool);
                for (PoolMember m : pool.getMembers()) {
                    if (m.getMemberPoolId() != null) {
                        parentOf.put(m.getMemberPoolId(), pool);
                    }
                }
            }
            Map<Long, PoolView> views = new HashMap<>();
            for (Pool pool : pools) {
                view(pool, byId, parentOf, people, sheetPot, today, views);
            }
            List<PoolView> out = new ArrayList<>(pools.stream().map(p -> views.get(p.getId())).toList());
            for (Crew crew : crews) {
                PoolView v = of(crewPool(crew), byId, people, sheetPot, today);
                out.add(new PoolView(-crew.getId(), v.name(), v.members(), v.totalShare(), v.balanced(), true));
            }
            return out;
        }

        /** A crew as a pool: its lead (when it has one) and then its members, 100.00 split equally between them,
         *  each rounded half to even to two decimals, the remainder on the first; none when it has no one. */
        private static Pool crewPool(Crew crew) {
            Pool pool = new Pool(crew.getSheetId(), crew.getName());
            List<Long> ids = new ArrayList<>();
            if (crew.getLeadId() != null) {
                ids.add(crew.getLeadId());
            }
            crew.getMembers().forEach(m -> ids.add(m.getPersonId()));
            if (ids.isEmpty()) {
                return pool;
            }
            BigDecimal each = HUNDRED.divide(BigDecimal.valueOf(ids.size()), 2, RoundingMode.HALF_EVEN);
            BigDecimal lead = HUNDRED.subtract(each.multiply(BigDecimal.valueOf(ids.size() - 1)));
            for (int i = 0; i < ids.size(); i++) {
                pool.addMember(ids.get(i), i == 0 ? lead : each, null, null);
            }
            return pool;
        }

        private static PoolView view(Pool pool, Map<Long, Pool> byId, Map<Long, Pool> parentOf,
                Map<Long, Person> people, BigDecimal sheetPot, LocalDate today, Map<Long, PoolView> views) {
            PoolView done = views.get(pool.getId());
            if (done != null) {
                return done;
            }
            BigDecimal pot = sheetPot;
            Pool parent = parentOf.get(pool.getId());
            if (parent != null) {
                PoolView above = view(parent, byId, parentOf, people, sheetPot, today, views);
                String cut = above.members().stream().filter(m -> pool.getId().equals(m.poolId())).findFirst()
                        .map(PoolMemberView::cut).orElse(null);
                pot = cut == null ? null : new BigDecimal(cut);
            }
            PoolView v = of(pool, byId, people, pot, today);
            views.put(pool.getId(), v);
            return v;
        }

        private static PoolView of(Pool pool, Map<Long, Pool> pools, Map<Long, Person> people, BigDecimal pot,
                LocalDate today) {
            List<PoolMember> all = pool.getMembers();
            BigDecimal total = BigDecimal.ZERO.setScale(2);
            boolean[] retired = new boolean[all.size()];
            boolean[] held = new boolean[all.size()];
            boolean[] active = new boolean[all.size()];
            boolean allRetired = true;
            for (int i = 0; i < all.size(); i++) {
                Long personId = all.get(i).getPersonId();
                Person p = personId == null ? null : people.get(personId);
                retired[i] = p != null && p.isRetired();
                held[i] = p != null && today != null && p.heldOn(today);
                active[i] = today == null || all.get(i).activeOn(today);
                if (active[i]) {
                    total = total.add(all.get(i).getShare());
                    allRetired &= retired[i];
                }
            }
            boolean balanced = total.compareTo(HUNDRED) == 0;
            BigDecimal[] cuts = balanced && pot != null && !allRetired ? cuts(all, retired, held, active, pot) : new BigDecimal[all.size()];
            List<PoolMemberView> members = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                PoolMember m = all.get(i);
                String name;
                if (m.getMemberPoolId() != null) {
                    Pool member = pools.get(m.getMemberPoolId());
                    name = member == null ? "" : member.getName();
                } else {
                    Person p = people.get(m.getPersonId());
                    name = p == null ? "" : p.getName();
                }
                members.add(new PoolMemberView(m.getPersonId(), m.getMemberPoolId(), name,
                        m.getShare().setScale(2).toPlainString(), retired[i],
                        cuts[i] == null ? null : cuts[i].toPlainString(), m.getFromDate(), m.getToDate(),
                        active[i], List.of()));
            }
            return new PoolView(pool.getId() == null ? 0 : pool.getId(), pool.getName(), members,
                    total.setScale(2).toPlainString(), balanced, false);
        }

        /** Each member's cut of the pot: the pot times their share divided by the shares of the members with a
         *  cut added up, rounded half to even to two decimals (0.00 for a retired one or one on hold, whose share is
         *  spread over the others in proportion to theirs); the
         *  remainder, the pot minus the cuts added up, goes to the member with the smallest non-zero cut, the
         *  latest added on a tie (when no cut is non-zero, to the last member with a cut and a share), so the cuts
         *  total exactly the pot. When no member has a cut, each is 0.00.
         *  A member not active on the sheet's today has none at all. */
        private static BigDecimal[] cuts(List<PoolMember> members, boolean[] retired, boolean[] held,
                boolean[] active, BigDecimal pot) {
            BigDecimal[] cuts = new BigDecimal[members.size()];
            BigDecimal shares = BigDecimal.ZERO;
            for (int i = 0; i < members.size(); i++) {
                if (active[i] && !retired[i] && !held[i]) {
                    shares = shares.add(members.get(i).getShare());
                }
            }
            BigDecimal sum = BigDecimal.ZERO.setScale(2);
            int smallest = -1;
        int last = -1;
            for (int i = 0; i < members.size(); i++) {
                if (!active[i]) {
                    continue;
                }
                BigDecimal share = members.get(i).getShare();
                boolean cut = !retired[i] && !held[i] && shares.signum() > 0;
                cuts[i] = cut ? pot.multiply(share).divide(shares, 2, RoundingMode.HALF_EVEN)
                        : BigDecimal.ZERO.setScale(2);
                sum = sum.add(cuts[i]);
                if (cut && share.signum() > 0) {
                    last = i;
                }
                if (cut && cuts[i].signum() != 0
                        && (smallest < 0 || cuts[i].compareTo(cuts[smallest]) <= 0)) {
                    smallest = i;
                }
            }
            int to = smallest >= 0 ? smallest : last;
            if (to >= 0) {
                cuts[to] = cuts[to].add(pot.setScale(2).subtract(sum));
            }
            return cuts;
        }
    }

    /** The views with each member's cut at the end of each period whose to-date is on or before today, by
     *  from-date: its cut in the pools as they stood then (then, by that date). */
    static List<PoolView> withHistory(List<PoolView> views, List<Period> periods, LocalDate today,
            Function<LocalDate, List<PoolView>> then) {
        List<Period> ended = periods.stream()
                .filter(p -> today != null && p.getToDate() != null && !p.getToDate().isAfter(today)).toList();
        List<List<PoolView>> past = ended.stream().map(p -> then.apply(p.getToDate())).toList();
        List<PoolView> out = new ArrayList<>();
        for (PoolView v : views) {
            List<PoolMemberView> members = new ArrayList<>();
            for (PoolMemberView m : v.members()) {
                List<PeriodCut> cuts = new ArrayList<>();
                for (int i = 0; i < ended.size(); i++) {
                    String cut = past.get(i).stream().filter(p -> p.id() == v.id() && p.crew() == v.crew())
                            .flatMap(p -> p.members().stream()).filter(m::same).findFirst()
                            .map(PoolMemberView::cut).orElse(null);
                    cuts.add(new PeriodCut(ended.get(i).getName(), cut));
                }
                members.add(m.withCuts(cuts));
            }
            out.add(new PoolView(v.id(), v.name(), members, v.totalShare(), v.balanced(), v.crew()));
        }
        return out;
    }

    public record PoolRequest(@Size(max = 200) String name) {
    }

    public record PoolMemberRequest(Long personId, Long poolId, BigDecimal share, LocalDate from, LocalDate to) {
    }

    private final PoolService service;
    private final CrewService crews;
    private final PersonRepository people;
    private final SheetService sheets;
    private final PeriodService periods;
    private final AsOf asOf;

    public PoolController(PoolService service, CrewService crews, PersonRepository people, SheetService sheets,
            PeriodService periods, AsOf asOf) {
        this.periods = periods;
        this.service = service;
        this.crews = crews;
        this.people = people;
        this.sheets = sheets;
        this.asOf = asOf;
    }

    /** The sheet's pools, in the order they were added; reckoned on the date, with its people and its pot as they
     *  stood then, when the screen views the sheet as of one. */
    @GetMapping
    @Transactional(readOnly = true)
    public List<PoolView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestHeader(value = AsOf.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        String sid = sheetId.trim();
        if (asOf == null) {
            return views(sid);
        }
        return withHistory(viewsAsOf(sid, asOf), this.asOf.periods(sid, asOf), asOf, d -> viewsAsOf(sid, d));
    }

    /** The pools as they stood at the end of a date: their members, the people, the pot and the holds then, the
     *  date as the sheet's today. */
    private List<PoolView> viewsAsOf(String sheetId, LocalDate date) {
        Map<Long, Person> then = asOf.people(sheetId, date).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        return PoolView.of(asOf.pools(sheetId, date), asOf.crews(sheetId, date), then,
                asOf.sheet(sheetId, date).getPot(), date);
    }

    /** Add a pool to the sheet. */
    @PostMapping
    public PoolView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PoolRequest request) {
        String sid = sheetId.trim();
        return view(sid, service.add(sid, user.trim(), request.name()));
    }

    /** Add a member to a pool with a share: a person, or another pool. */
    @PostMapping("/{poolId}/members")
    public PoolView addMember(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable long poolId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PoolMemberRequest request) {
        String sid = sheetId.trim();
        Pool pool = service.addMember(sid, user.trim(), poolId, request.personId(), request.poolId(),
                request.share(), request.from(), request.to());
        return view(sid, pool);
    }

    private List<PoolView> views(String sheetId) {
        LocalDate today = sheets.today(sheetId);
        return withHistory(PoolView.of(service.list(sheetId), crews.list(sheetId), people(sheetId), sheets.pot(sheetId),
                today), periods.list(sheetId), today, d -> viewsAsOf(sheetId, d));
    }

    private PoolView view(String sheetId, Pool pool) {
        return views(sheetId).stream().filter(v -> !v.crew() && v.id() == pool.getId()).findFirst().orElseThrow();
    }

    private Map<Long, Person> people(String sheetId) {
        return people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
    }

    /** A pool or member that cannot be added is refused with the reason, for the screen to show. */
    @ExceptionHandler(PoolRefusedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> refused(PoolRefusedException e) {
        return Map.of("message", e.getMessage());
    }
}
