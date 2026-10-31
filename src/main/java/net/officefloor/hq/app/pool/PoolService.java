package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The pools on a sheet and their members. */
@Service
public class PoolService {

    /** The most a member's share can be, and what a balanced pool's shares total. */
    public static final BigDecimal FULL = new BigDecimal("100.00");

    /** The most pools a chain of pools, each a member of the next, can hold. */
    public static final int MAX_DEPTH = 3;

    /** A member of a pool as it stands: a person or another pool (exactly one is set), its share, whether the
     *  person has a hold in force on the sheet's today, the membership's from-date and to-date (null: none), and
     *  whether those dates include that today. */
    public record Member(Person person, Pool pool, BigDecimal share, boolean held, LocalDate from, LocalDate to,
            boolean inForce) {

        /** What the share counts toward the pool's total: nothing once the person is retired, whatever the date, or
         *  while the membership's dates do not include the sheet's today. */
        public BigDecimal counted() {
            return !inForce || (person != null && person.isRetired()) ? BigDecimal.ZERO.setScale(2) : share;
        }

        /** What the share counts toward the cuts: nothing while held on the sheet's today, else what it counts. */
        public BigDecimal cutShare() {
            return held ? BigDecimal.ZERO.setScale(2) : counted();
        }
    }

    /** A pool with its members, in the order they were added, and its pot (null: none): the sheet's pot for a pool
     *  that is a member of none, else its cut in the pool above. */
    public record PoolWithMembers(Pool pool, List<Member> members, BigDecimal pot) {

        /** The members' shares added up, a retired member's, or one whose dates do not include the sheet's today,
         *  counting as 0.00. */
        public BigDecimal totalShare() {
            return members.stream().map(Member::counted).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        }

        /** Balanced when the shares total exactly 100.00. */
        public boolean balanced() {
            return totalShare().compareTo(FULL) == 0;
        }

        /** Each member's cut of the pot, in the order of the members; none (null) when there is no pot or the pool
         *  is not balanced. A cut is the pot times the share over the shares of the members with a cut added up,
         *  rounded half to even to two decimals, 0.00 for a retired member, one held on the sheet's today or one whose
         *  dates do not include it (shown as no cut); what the
         *  cuts fall short of (or over) the pot goes to the member with the largest share with a cut, the earliest
         *  added on a tie, so the cuts total exactly the pot. When no member has a cut, each is 0.00. */
        public List<BigDecimal> cuts() {
            if (pot == null || !balanced()) {
                return null;
            }
            BigDecimal withCut = members.stream().map(Member::cutShare)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            if (withCut.signum() == 0) {
                return new ArrayList<>(members.stream().map(m -> BigDecimal.ZERO.setScale(2)).toList());
            }
            List<BigDecimal> cuts = new ArrayList<>(members.stream()
                    .map(m -> pot.multiply(m.cutShare()).divide(withCut, 2, RoundingMode.HALF_EVEN)).toList());
            BigDecimal remainder = pot.subtract(cuts.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add));
            int largest = 0;
            for (int i = 1; i < members.size(); i++) {
                if (members.get(i).cutShare().compareTo(members.get(largest).cutShare()) > 0) {
                    largest = i;
                }
            }
            cuts.set(largest, cuts.get(largest).add(remainder));
            return cuts;
        }
    }

    private final PoolRepository pools;
    private final PoolMemberRepository members;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final Audit audit;

    public PoolService(PoolRepository pools, PoolMemberRepository members, PersonRepository people,
            SheetService sheets, SheetRepository sheetStore, Audit audit) {
        this.pools = pools;
        this.members = members;
        this.people = people;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.audit = audit;
    }

    /** The sheet's pools, in the order they were added, each with its members and its pot. */
    @Transactional(readOnly = true)
    public List<PoolWithMembers> list(String sheetId) {
        List<Pool> list = pools.findBySheetIdOrderByIdAsc(sheetId);
        BigDecimal sheetPot = sheetStore.findById(sheetId).map(Sheet::getPot).orElse(null);
        LocalDate today = sheetStore.findById(sheetId).map(Sheet::getToday).orElse(null);
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, Pool> poolsById = list.stream().collect(Collectors.toMap(Pool::getId, Function.identity()));
        Map<Long, List<PoolMember>> byPool = members.findByPoolIdInOrderByIdAsc(list.stream().map(Pool::getId).toList())
                .stream().collect(Collectors.groupingBy(PoolMember::getPoolId));
        Map<Long, List<Member>> membersOf = new HashMap<>();
        Map<Long, Long> parentOf = new HashMap<>();
        for (Pool p : list) {
            List<Member> of = new ArrayList<>();
            for (PoolMember m : byPool.getOrDefault(p.getId(), List.of())) {
                if (m.getMemberPoolId() != null) {
                    Pool child = poolsById.get(m.getMemberPoolId());
                    if (child != null) {
                        of.add(new Member(null, child, m.getShare(), false, m.getFrom(), m.getTo(),
                                m.inForceOn(today)));
                        parentOf.put(child.getId(), p.getId());
                    }
                } else if (byId.containsKey(m.getPersonId())) {
                    Person person = byId.get(m.getPersonId());
                    of.add(new Member(person, null, m.getShare(), person.heldOn(today), m.getFrom(),
                            m.getTo(), m.inForceOn(today)));
                }
            }
            membersOf.put(p.getId(), of);
        }
        Map<Long, PoolWithMembers> done = new HashMap<>();
        return list.stream().map(p -> withPot(p.getId(), poolsById, membersOf, parentOf, sheetPot, done, 0)).toList();
    }

    /** The pool with its members and its pot: the sheet's pot when it is a member of none, else its cut in the pool
     *  above (none when that pool shows no cuts or its membership there is not in force on the sheet's today). */
    private static PoolWithMembers withPot(Long poolId, Map<Long, Pool> poolsById, Map<Long, List<Member>> membersOf,
            Map<Long, Long> parentOf, BigDecimal sheetPot, Map<Long, PoolWithMembers> done, int depth) {
        PoolWithMembers known = done.get(poolId);
        if (known != null) {
            return known;
        }
        Long parentId = parentOf.get(poolId);
        BigDecimal pot = sheetPot;
        if (parentId != null) {
            pot = null;
            if (depth <= poolsById.size()) {
                PoolWithMembers parent = withPot(parentId, poolsById, membersOf, parentOf, sheetPot, done, depth + 1);
                List<BigDecimal> cuts = parent.cuts();
                if (cuts != null) {
                    for (int i = 0; i < parent.members().size(); i++) {
                        Member member = parent.members().get(i);
                        if (member.pool() != null && member.pool().getId().equals(poolId) && member.inForce()) {
                            pot = cuts.get(i);
                        }
                    }
                }
            }
        }
        PoolWithMembers made = new PoolWithMembers(poolsById.get(poolId), membersOf.get(poolId), pot);
        done.put(poolId, made);
        return made;
    }

    /** Add a pool by its name, trimmed; refused when blank or already a pool on the sheet. */
    @Transactional
    public Pool add(String sheetId, String user, String name) {
        String key = name == null ? "" : name.strip();
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A pool has a name");
        }
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        if (pools.existsBySheetIdAndName(sheetId, key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That pool is already on the sheet");
        }
        Pool saved;
        try {
            saved = pools.saveAndFlush(new Pool(sheetId, key));
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That pool is already on the sheet");
        }
        audit.recordChangeOnCommit(sheetId, user, "POOL_ADDED sheet=" + sheetId + " by=" + user
                + " name=" + saved.getName());
        return saved;
    }

    /** Add a member to the pool with a share percent: a person of the sheet (personId) or another pool of it
     *  (memberPoolId), exactly one given. Refused when the share is not more than 0.00 and at most 100.00 with up to
     *  two decimals, when the person is already in it, or when the pool member is already a member of a pool, would
     *  contain itself through any chain, or would make a chain of pools more than three deep. The membership may
     *  carry a from-date and a to-date, each optional; refused when the to-date is before the from-date. */
    @Transactional
    public PoolWithMembers addMember(String sheetId, String user, long poolId, Long personId, Long memberPoolId,
            BigDecimal share, LocalDate from, LocalDate to) {
        if ((personId == null) == (memberPoolId == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A member is a person or a pool");
        }
        if (share == null || share.signum() <= 0 || share.compareTo(FULL) > 0
                || share.stripTrailingZeros().scale() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A share is more than 0.00 and at most 100.00, with up to two decimals");
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A to-date is not before the from-date");
        }
        BigDecimal scaled = share.setScale(2, RoundingMode.UNNECESSARY);
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        Pool pool = pools.findById(poolId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool on this sheet"));
        String name;
        PoolMember member;
        if (personId != null) {
            Person person = people.findById(personId).filter(p -> p.getSheetId().equals(sheetId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
            if (members.existsByPoolIdAndPersonId(pool.getId(), personId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "That person is already in the pool");
            }
            name = person.getName();
            member = new PoolMember(pool.getId(), personId, scaled);
        } else {
            Pool child = pools.findById(memberPoolId).filter(p -> p.getSheetId().equals(sheetId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool on this sheet"));
            checkNesting(sheetId, pool, child);
            name = child.getName();
            member = PoolMember.ofPool(pool.getId(), child.getId(), scaled);
        }
        member.setDates(from, to);
        try {
            members.saveAndFlush(member);
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That member is already in a pool");
        }
        audit.recordChangeOnCommit(sheetId, user, "POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user
                + " pool=" + pool.getName() + " name=" + name + " share=" + scaled.toPlainString() + (from == null ? "" : " from=" + from)
                + (to == null ? "" : " to=" + to));
        return list(sheetId).stream().filter(p -> p.pool().getId().equals(pool.getId())).findFirst().orElseThrow();
    }

    /** Refuse making the child pool a member of the parent when it is already a member of a pool, when the parent
     *  is the child or within it, or when the chain of pools would be more than {@link #MAX_DEPTH} deep. */
    private void checkNesting(String sheetId, Pool parent, Pool child) {
        if (members.findByMemberPoolId(child.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That pool is already a member of a pool");
        }
        List<Long> ids = pools.findBySheetIdOrderByIdAsc(sheetId).stream().map(Pool::getId).toList();
        Map<Long, Long> parentOf = new HashMap<>();
        Map<Long, List<Long>> childrenOf = new HashMap<>();
        for (PoolMember m : members.findByPoolIdInOrderByIdAsc(ids)) {
            if (m.getMemberPoolId() != null) {
                parentOf.put(m.getMemberPoolId(), m.getPoolId());
                childrenOf.computeIfAbsent(m.getPoolId(), k -> new ArrayList<>()).add(m.getMemberPoolId());
            }
        }
        // How deep the parent sits: 1 for a pool that is a member of none.
        int above = 0;
        for (Long at = parent.getId(); at != null && above <= ids.size(); at = parentOf.get(at)) {
            if (at.equals(child.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A pool cannot contain itself");
            }
            above++;
        }
        if (above + height(child.getId(), childrenOf, 0, ids.size()) > MAX_DEPTH) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A chain of pools is at most " + MAX_DEPTH + " deep");
        }
    }

    /** How many pools deep the chain below a pool goes, counting the pool itself. */
    private static int height(Long poolId, Map<Long, List<Long>> childrenOf, int depth, int limit) {
        int most = 0;
        if (depth <= limit) {
            for (Long c : childrenOf.getOrDefault(poolId, List.of())) {
                most = Math.max(most, height(c, childrenOf, depth + 1, limit));
            }
        }
        return 1 + most;
    }
}
