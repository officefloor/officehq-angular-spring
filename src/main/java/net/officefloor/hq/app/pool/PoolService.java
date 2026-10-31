package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
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

    /** A member of a pool as it stands: the person, their share, and whether they are retired. */
    public record Member(Person person, BigDecimal share) {

        /** What the share counts toward the pool's total: nothing once the person is retired, whatever the date. */
        public BigDecimal counted() {
            return person.isRetired() ? BigDecimal.ZERO.setScale(2) : share;
        }
    }

    /** A pool with its members, in the order they were added, and the sheet's pot (null: none). */
    public record PoolWithMembers(Pool pool, List<Member> members, BigDecimal pot) {

        /** The members' shares added up, a retired member's counting as 0.00. */
        public BigDecimal totalShare() {
            return members.stream().map(Member::counted).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        }

        /** Balanced when the shares total exactly 100.00. */
        public boolean balanced() {
            return totalShare().compareTo(FULL) == 0;
        }

        /** Each member's cut of the pot, in the order of the members; none (null) when there is no pot or the pool
         *  is not balanced. A cut is the pot times the share over 100, rounded half to even to two decimals, 0.00 for
         *  a retired member; what the cuts fall short of (or over) the pot goes to the member with the largest share
         *  counted, the earliest added on a tie, so the cuts total exactly the pot. */
        public List<BigDecimal> cuts() {
            if (pot == null || !balanced()) {
                return null;
            }
            List<BigDecimal> cuts = new ArrayList<>(members.stream()
                    .map(m -> pot.multiply(m.counted()).divide(FULL, 2, RoundingMode.HALF_EVEN)).toList());
            BigDecimal remainder = pot.subtract(cuts.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add));
            int largest = 0;
            for (int i = 1; i < members.size(); i++) {
                if (members.get(i).counted().compareTo(members.get(largest).counted()) > 0) {
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

    /** The sheet's pools, in the order they were added, each with its members. */
    @Transactional(readOnly = true)
    public List<PoolWithMembers> list(String sheetId) {
        List<Pool> list = pools.findBySheetIdOrderByIdAsc(sheetId);
        BigDecimal pot = sheetStore.findById(sheetId).map(Sheet::getPot).orElse(null);
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, List<PoolMember>> byPool = members.findByPoolIdInOrderByIdAsc(list.stream().map(Pool::getId).toList())
                .stream().collect(Collectors.groupingBy(PoolMember::getPoolId));
        return list.stream().map(p -> new PoolWithMembers(p, byPool.getOrDefault(p.getId(), List.of()).stream()
                .filter(m -> byId.containsKey(m.getPersonId()))
                .map(m -> new Member(byId.get(m.getPersonId()), m.getShare())).toList(), pot)).toList();
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

    /** Add a person of the sheet to the pool with a share percent; refused when the person is already in it,
     *  or the share is not more than 0.00 and at most 100.00 with up to two decimals. */
    @Transactional
    public PoolWithMembers addMember(String sheetId, String user, long poolId, long personId, BigDecimal share) {
        if (share == null || share.signum() <= 0 || share.compareTo(FULL) > 0
                || share.stripTrailingZeros().scale() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A share is more than 0.00 and at most 100.00, with up to two decimals");
        }
        BigDecimal scaled = share.setScale(2, RoundingMode.UNNECESSARY);
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        Pool pool = pools.findById(poolId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool on this sheet"));
        Person person = people.findById(personId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        if (members.existsByPoolIdAndPersonId(pool.getId(), personId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That person is already in the pool");
        }
        try {
            members.saveAndFlush(new PoolMember(pool.getId(), personId, scaled));
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That person is already in the pool");
        }
        audit.recordChangeOnCommit(sheetId, user, "POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user
                + " pool=" + pool.getName() + " name=" + person.getName() + " share=" + scaled.toPlainString());
        return list(sheetId).stream().filter(p -> p.pool().getId().equals(pool.getId())).findFirst().orElseThrow();
    }
}
