package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonHold;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;

/** Each person's cut total: their cuts of the pot across every pool they are a member of, worked out
 *  as the screen shows them. */
@Service
public class PoolCutService {

    private static final BigDecimal NONE = BigDecimal.ZERO.setScale(2);

    private static final BigDecimal FULL = new BigDecimal("100.00");

    /** A member: a person or a pool, its share and its dates (null: no bound). */
    private record Member(Long personId, Long memberPoolId, BigDecimal share, LocalDate from, LocalDate to) {
    }

    /** A pool, or a crew as one (its id negated), with its members in the order they were added. */
    private record CutPool(Long id, List<Member> members) {
    }

    /** A member's share, whether it is a retired person, a person with a hold in force on the day, and
     *  whether its dates leave it out on the day. */
    private record Share(BigDecimal share, boolean retired, boolean held, boolean out) {
        boolean cutting() {
            return !retired && !held && !out;
        }
    }

    private final PoolService pools;
    private final PersonService personService;
    private final SettingService settings;
    private final SheetService sheets;

    public PoolCutService(PoolService pools, PersonService personService, SettingService settings,
            SheetService sheets) {
        this.pools = pools;
        this.personService = personService;
        this.settings = settings;
        this.sheets = sheets;
    }

    /** Each person's cut total on the sheet as it stood at the end of a day, that day standing in for
     *  the sheet's today (null: as it shows them now), by person id; a person in no pool that shows cuts
     *  has 0.00. */
    public Map<Long, BigDecimal> cutTotals(String sheetId, LocalDate asOf, List<Person> sheetPeople) {
        LocalDate today = asOf != null ? asOf : sheets.today(sheetId);
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        sheetPeople.forEach(person -> totals.put(person.getId(), NONE));
        BigDecimal pot = asOf != null ? settings.pot(sheetId, asOf) : settings.pot(sheetId);
        if (pot == null) {
            return totals;
        }
        List<CutPool> list = new ArrayList<>();
        pools.list(sheetId, asOf).forEach(p -> list.add(new CutPool(p.pool().getId(), p.members().stream()
                .map(m -> new Member(m.getPersonId(), m.getMemberPoolId(), m.getShare(), m.getFrom(), m.getTo()))
                .toList())));
        pools.crewPools(sheetId, asOf).forEach(c -> {
            List<Member> members = new ArrayList<>();
            for (int i = 0; i < c.personIds().size(); i++) {
                members.add(new Member(c.personIds().get(i), null, c.shares().get(i), null, null));
            }
            list.add(new CutPool(-c.crew().getId(), members));
        });
        Map<Long, Person> byId = new HashMap<>();
        sheetPeople.forEach(person -> byId.put(person.getId(), person));
        Map<Long, List<PersonHold>> holds = personService.holds(sheetId, asOf);
        Map<Long, CutPool> above = new HashMap<>();
        list.forEach(p -> p.members().forEach(m -> {
            if (m.memberPoolId() != null) {
                above.put(m.memberPoolId(), p);
            }
        }));
        for (CutPool pool : list) {
            List<BigDecimal> cut = cutsOf(pool, new HashSet<>(), above, byId, holds, pot, today);
            if (cut == null) {
                continue;
            }
            for (int i = 0; i < pool.members().size(); i++) {
                Long personId = pool.members().get(i).personId();
                if (personId != null && totals.containsKey(personId)) {
                    totals.merge(personId, cut.get(i), BigDecimal::add);
                }
            }
        }
        return totals;
    }

    /** A pool's cuts, in its members' order; null when it is unbalanced, every member is retired or it
     *  has no pot: a pool that is a member of none has the sheet's pot, any other its cut in the pool above. */
    private List<BigDecimal> cutsOf(CutPool pool, Set<Long> seen, Map<Long, CutPool> above, Map<Long, Person> byId,
            Map<Long, List<PersonHold>> holds, BigDecimal pot, LocalDate today) {
        if (!seen.add(pool.id())) {
            return null;
        }
        List<Share> shares = pool.members().stream().map(m -> {
            Person person = m.personId() != null ? byId.get(m.personId()) : null;
            boolean retired = person != null && person.isRetired();
            boolean held = m.personId() != null
                    && holds.getOrDefault(m.personId(), List.of()).stream().anyMatch(h -> h.contains(today));
            boolean out = (m.from() != null && today.isBefore(m.from())) || (m.to() != null && m.to().isBefore(today));
            return new Share(m.share(), retired, held, out);
        }).toList();
        BigDecimal total = shares.stream().filter(s -> !s.out()).map(Share::share).reduce(NONE, BigDecimal::add);
        if (total.compareTo(FULL) != 0 || shares.stream().allMatch(Share::retired)) {
            return null;
        }
        CutPool parent = above.get(pool.id());
        BigDecimal from;
        if (parent != null) {
            List<BigDecimal> parentCuts = cutsOf(parent, new HashSet<>(seen), above, byId, holds, pot, today);
            int at = -1;
            for (int i = 0; i < parent.members().size(); i++) {
                if (pool.id().equals(parent.members().get(i).memberPoolId())) {
                    at = i;
                    break;
                }
            }
            from = parentCuts != null && at >= 0 ? parentCuts.get(at) : null;
        } else {
            from = pot;
        }
        return from == null ? null : cuts(from.setScale(2, RoundingMode.UNNECESSARY), shares);
    }

    /** Each member's cut of the pot, in the members' order: one that is not cutting 0.00, its share spread
     *  over the others in proportion; any other's the pot times its share divided by the cutting shares
     *  added up, rounded half to even. The remainder goes to the member with the smallest non-zero cut,
     *  the latest added on a tie; with none, to the last cutting member. */
    private static List<BigDecimal> cuts(BigDecimal pot, List<Share> members) {
        BigDecimal shares = members.stream().filter(Share::cutting).map(Share::share).reduce(NONE, BigDecimal::add);
        List<BigDecimal> list = new ArrayList<>();
        if (shares.signum() == 0) {
            members.forEach(m -> list.add(NONE));
            return list;
        }
        for (Share m : members) {
            list.add(m.cutting() ? pot.multiply(m.share()).divide(shares, 2, RoundingMode.HALF_EVEN) : NONE);
        }
        int smallest = -1;
        int last = -1;
        for (int i = 0; i < members.size(); i++) {
            if (!members.get(i).cutting()) {
                continue;
            }
            last = i;
            if (list.get(i).signum() != 0 && (smallest < 0 || list.get(i).compareTo(list.get(smallest)) <= 0)) {
                smallest = i;
            }
        }
        int taker = smallest >= 0 ? smallest : last;
        if (taker >= 0) {
            BigDecimal sum = list.stream().reduce(NONE, BigDecimal::add);
            list.set(taker, list.get(taker).add(pot.subtract(sum)));
        }
        return list;
    }
}
