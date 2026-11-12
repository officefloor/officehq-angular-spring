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
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
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
     *  whatever the date) counts 0.00 toward the total. Its cut of the pool's pot (two decimals; 0.00 for a person
     *  on hold on the sheet's today, whose share is spread over the others); none when the pool is unbalanced or
     *  has no pot. A member whose dates (from, to: optional) do not include the sheet's today is left out
     *  entirely: it counts 0.00 toward the total and has no cut. */
    public record PoolMemberView(Long personId, Long poolId, String name, String share, boolean retired, String cut,
            LocalDate from, LocalDate to, boolean active) {
    }

    /** A pool, with its total share and whether that is exactly 100.00. */
    public record PoolView(long id, String name, List<PoolMemberView> members, String totalShare, boolean balanced) {

        /** The sheet's pools as views, in their order. A pool that is a member of another has as its pot its cut
         *  in the pool above (none when that is unbalanced or has none); one that is a member of none has the
         *  sheet's pot. A person on hold on the sheet's today gets no cut from any pool. */
        static List<PoolView> of(List<Pool> pools, Map<Long, Person> people, BigDecimal sheetPot, LocalDate today) {
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
            return pools.stream().map(p -> views.get(p.getId())).toList();
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
            for (int i = 0; i < all.size(); i++) {
                Long personId = all.get(i).getPersonId();
                Person p = personId == null ? null : people.get(personId);
                retired[i] = p != null && p.isRetired();
                held[i] = p != null && today != null && p.heldOn(today);
                active[i] = today == null || all.get(i).activeOn(today);
                if (!retired[i] && active[i]) {
                    total = total.add(all.get(i).getShare());
                }
            }
            boolean balanced = total.compareTo(HUNDRED) == 0;
            BigDecimal[] cuts = balanced && pot != null ? cuts(all, retired, held, active, pot) : new BigDecimal[all.size()];
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
                        active[i]));
            }
            return new PoolView(pool.getId(), pool.getName(), members, total.setScale(2).toPlainString(), balanced);
        }

        /** Each member's cut of the pot: the pot times their share divided by the shares of the members with a
         *  cut added up, rounded half to even to two decimals (0.00 for a retired one or one on hold); the
         *  remainder, the pot minus the cuts added up, goes to the member with a cut with the largest share, the
         *  earliest added on a tie, so the cuts total exactly the pot. When no member has a cut, each is 0.00.
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
            int largest = -1;
            for (int i = 0; i < members.size(); i++) {
                if (!active[i]) {
                    continue;
                }
                BigDecimal share = members.get(i).getShare();
                boolean cut = !retired[i] && !held[i] && shares.signum() > 0;
                cuts[i] = cut ? pot.multiply(share).divide(shares, 2, RoundingMode.HALF_EVEN)
                        : BigDecimal.ZERO.setScale(2);
                sum = sum.add(cuts[i]);
                if (cut && (largest < 0 || share.compareTo(members.get(largest).getShare()) > 0)) {
                    largest = i;
                }
            }
            if (largest >= 0) {
                cuts[largest] = cuts[largest].add(pot.setScale(2).subtract(sum));
            }
            return cuts;
        }
    }

    public record PoolRequest(@Size(max = 200) String name) {
    }

    public record PoolMemberRequest(Long personId, Long poolId, BigDecimal share, LocalDate from, LocalDate to) {
    }

    private final PoolService service;
    private final PersonRepository people;
    private final SheetService sheets;

    public PoolController(PoolService service, PersonRepository people, SheetService sheets) {
        this.service = service;
        this.people = people;
        this.sheets = sheets;
    }

    /** The sheet's pools, in the order they were added. */
    @GetMapping
    @Transactional(readOnly = true)
    public List<PoolView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return views(sheetId.trim());
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
        return PoolView.of(service.list(sheetId), people(sheetId), sheets.pot(sheetId), sheets.today(sheetId));
    }

    private PoolView view(String sheetId, Pool pool) {
        return views(sheetId).stream().filter(v -> v.id() == pool.getId()).findFirst().orElseThrow();
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
