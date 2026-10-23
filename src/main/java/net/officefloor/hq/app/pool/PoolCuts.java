package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.crew.Crew;
import net.officefloor.hq.app.crew.CrewMember;
import net.officefloor.hq.app.crew.CrewMemberRepository;
import net.officefloor.hq.app.crew.CrewRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.pool.PoolService.Member;
import net.officefloor.hq.app.pool.PoolService.PoolWithMembers;
import net.officefloor.hq.app.sheet.Sheet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sheet's pools as listed, and each person's cut total as the screen shows it: their cuts across every
 * pool they are a member of on the sheet's today, added up. Needs nothing that moves figures, so the
 * rollups can read the cuts.
 */
@Service
public class PoolCuts {

    private static final BigDecimal MAX_SHARE = new BigDecimal("100.00");

    /** A pool's total share, in hundredths of a percent, when it is balanced. */
    private static final long BALANCED = 10000;

    private final PoolRepository pools;
    private final PoolMemberRepository members;
    private final PersonRepository people;
    private final CrewRepository crews;
    private final CrewMemberRepository crewMembers;

    public PoolCuts(PoolRepository pools, PoolMemberRepository members, PersonRepository people,
            CrewRepository crews, CrewMemberRepository crewMembers) {
        this.pools = pools;
        this.members = members;
        this.people = people;
        this.crews = crews;
        this.crewMembers = crewMembers;
    }

    /**
     * The sheet's pools in the order they were added, with their members, then the crews' pools in the order
     * the crews were added.
     */
    @Transactional(readOnly = true)
    public List<PoolWithMembers> list(String sheetId) {
        List<Pool> stored = pools.findBySheetIdOrderByIdAsc(sheetId);
        List<Pool> all = new ArrayList<>(stored.stream().filter(p -> p.getCrewId() == null).toList());
        all.addAll(stored.stream().filter(p -> p.getCrewId() != null).toList());
        Map<Long, String> names = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Person::getName));
        Map<Long, String> poolNames = all.stream().collect(Collectors.toMap(Pool::getId, Pool::getName));
        Map<Long, List<Member>> byPool = members.findByPoolIdInOrderByIdAsc(all.stream().map(Pool::getId).toList())
                .stream().map(m -> new Member(m, m.getMemberPoolId() != null ? poolNames.get(m.getMemberPoolId())
                        : names.get(m.getPersonId())))
                .collect(Collectors.groupingBy(m -> m.member().getPoolId()));
        Map<Long, List<Member>> byCrew = crewPoolMembers(sheetId, all, names);
        return all.stream().map(p -> new PoolWithMembers(p, p.getCrewId() != null
                ? byCrew.getOrDefault(p.getId(), List.of()) : byPool.getOrDefault(p.getId(), List.of()))).toList();
    }

    /**
     * Each crew's pool's members by pool id: the lead, then the members in the order they were added, at
     * 100.00 split equally, each rounded half to even, the remainder on the lead. Not stored.
     */
    private Map<Long, List<Member>> crewPoolMembers(String sheetId, List<Pool> all, Map<Long, String> names) {
        Map<Long, Crew> byId = crews.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Crew::getId, Function.identity()));
        Map<Long, List<CrewMember>> crewMembersOf = crewMembers.findByCrewIdInOrderByIdAsc(byId.keySet()).stream()
                .collect(Collectors.groupingBy(CrewMember::getCrewId));
        Map<Long, List<Member>> result = new HashMap<>();
        for (Pool pool : all) {
            Crew crew = pool.getCrewId() == null ? null : byId.get(pool.getCrewId());
            if (crew == null) continue;
            List<Long> ids = new ArrayList<>(List.of(crew.getLeadId()));
            crewMembersOf.getOrDefault(crew.getId(), List.of()).forEach(m -> ids.add(m.getPersonId()));
            BigDecimal each = MAX_SHARE.divide(BigDecimal.valueOf(ids.size()), 2, RoundingMode.HALF_EVEN);
            BigDecimal lead = MAX_SHARE.subtract(each.multiply(BigDecimal.valueOf(ids.size() - 1)));
            List<Member> shares = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                shares.add(new Member(new PoolMember(pool.getId(), ids.get(i), i == 0 ? lead : each),
                        names.get(ids.get(i))));
            }
            result.put(pool.getId(), shares);
        }
        return result;
    }

    /**
     * Each person's cut total by person id, with two decimals, on the sheet's today with its pot: their cuts
     * across every pool they are a member of, added up; a pool that shows no cuts adds nothing, and a person
     * in none has none. Null sheet: none.
     */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> personCuts(Sheet sheet, Iterable<Person> sheetPeople) {
        Map<Long, BigDecimal> totals = new HashMap<>();
        if (sheet == null) {
            return totals;
        }
        LocalDate today = sheet.getToday();
        Set<Long> retired = new java.util.HashSet<>();
        Set<Long> held = new java.util.HashSet<>();
        for (Person person : sheetPeople) {
            if (person.retiredOn(today)) retired.add(person.getId());
            if (today != null && person.heldOn(today)) held.add(person.getId());
        }
        List<PoolWithMembers> all = list(sheet.getId());
        Map<Long, List<PoolMember>> inForce = new HashMap<>();
        Map<Long, Long> parents = new HashMap<>();
        for (PoolWithMembers pool : all) {
            inForce.put(pool.pool().getId(), pool.members().stream().map(Member::member)
                    .filter(m -> memberOn(m, today)).toList());
            pool.members().stream().map(Member::member).filter(m -> m.getMemberPoolId() != null)
                    .forEach(m -> parents.put(m.getMemberPoolId(), pool.pool().getId()));
        }
        Long pot = sheet.getPot() == null ? null : cents(sheet.getPot());
        Map<Long, Map<String, Long>> memo = new HashMap<>();
        for (PoolWithMembers pool : all) {
            Map<String, Long> cuts = cutsOf(pool.pool().getId(), inForce, parents, pot, retired, held, memo);
            for (Member member : pool.members()) {
                PoolMember m = member.member();
                Long c = cuts.get(key(m));
                if (m.getPersonId() != null && c != null) {
                    totals.merge(m.getPersonId(), BigDecimal.valueOf(c, 2), BigDecimal::add);
                }
            }
        }
        return totals;
    }

    /**
     * A pool's members' cuts in cents by member key: of the sheet's pot for a pool that is a member of none,
     * of its cut in the pool above for one that is; none when it is unbalanced, has no pot, or has every
     * member retired.
     */
    private static Map<String, Long> cutsOf(long poolId, Map<Long, List<PoolMember>> inForce, Map<Long, Long> parents,
            Long sheetPot, Set<Long> retired, Set<Long> held, Map<Long, Map<String, Long>> memo) {
        Map<String, Long> known = memo.get(poolId);
        if (known != null) {
            return known;
        }
        memo.put(poolId, Map.of());
        Long pot = null;
        Long parentId = parents.get(poolId);
        if (parentId != null) {
            if (inForce.containsKey(parentId)) {
                pot = cutsOf(parentId, inForce, parents, sheetPot, retired, held, memo).get("pool:" + poolId);
            }
        } else {
            pot = sheetPot;
        }
        List<PoolMember> poolMembers = inForce.getOrDefault(poolId, List.of());
        long total = poolMembers.stream().mapToLong(m -> cents(m.getShare())).sum();
        boolean allRetired = !poolMembers.isEmpty() && poolMembers.stream().allMatch(m -> isPersonIn(m, retired));
        Map<String, Long> found = total == BALANCED && pot != null && !allRetired
                ? cuts(poolMembers, pot, retired, held) : Map.of();
        memo.put(poolId, found);
        return found;
    }

    /**
     * Each member's cut of the pot, in cents by member key. A retired or held person gets 0; every other
     * member gets the pot times their share over the shares of the members with a cut, rounded half to
     * even; the remainder goes to the member with the smallest non-zero cut, the latest added on a tie, or
     * to the last member with a cut when every cut is zero, so the cuts total exactly the pot.
     */
    private static Map<String, Long> cuts(List<PoolMember> poolMembers, long pot, Set<Long> retired, Set<Long> held) {
        List<String> ids = new ArrayList<>();
        List<Long> shares = new ArrayList<>();
        for (PoolMember m : poolMembers) {
            ids.add(key(m));
            shares.add(isPersonIn(m, retired) || isPersonIn(m, held) ? 0L : cents(m.getShare()));
        }
        long total = 0;
        for (long share : shares) {
            total += share;
        }
        Map<String, Long> all = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            all.put(ids.get(i), total > 0 ? cut(pot, shares.get(i), total) : 0L);
        }
        String smallest = null;
        String lastWithCut = null;
        for (int i = 0; i < ids.size(); i++) {
            if (shares.get(i) <= 0) continue;
            String id = ids.get(i);
            lastWithCut = id;
            long c = all.get(id);
            if (c > 0 && (smallest == null || c <= all.get(smallest))) {
                smallest = id;
            }
        }
        if (smallest == null) {
            smallest = lastWithCut;
        }
        if (smallest != null) {
            long remainder = pot - all.values().stream().mapToLong(Long::longValue).sum();
            all.put(smallest, all.get(smallest) + remainder);
        }
        return all;
    }

    /** Cents times a share over the shares with a cut: whole cents rounded half to even. */
    private static long cut(long pot, long share, long shares) {
        long product = pot * share;
        long whole = product / shares;
        long twice = (product % shares) * 2;
        return twice > shares || (twice == shares && whole % 2 == 1) ? whole + 1 : whole;
    }

    /** Whether a membership is in force on a day (none: always). */
    private static boolean memberOn(PoolMember m, LocalDate day) {
        return day == null || ((m.getFromDate() == null || !m.getFromDate().isAfter(day))
                && (m.getToDate() == null || !day.isAfter(m.getToDate())));
    }

    /** A member's key: person:&lt;id&gt; or pool:&lt;id&gt;. */
    private static String key(PoolMember m) {
        return m.getMemberPoolId() != null ? "pool:" + m.getMemberPoolId() : "person:" + m.getPersonId();
    }

    private static boolean isPersonIn(PoolMember m, Set<Long> ids) {
        return m.getPersonId() != null && ids.contains(m.getPersonId());
    }

    private static long cents(BigDecimal value) {
        return value.movePointRight(2).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
    }
}
