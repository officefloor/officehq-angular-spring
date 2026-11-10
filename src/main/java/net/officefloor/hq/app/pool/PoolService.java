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
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sheet's pools: each a name, at most once on the sheet, and its members, each a person on the
 * sheet at most once per pool with a share percent (two decimals, above 0.00 and at most 100.00).
 * A pool's total adds up its members' shares, a retired member's (one with a retirement date set,
 * whatever the date) counting as 0.00; the pool is balanced when the total is exactly 100.00.
 * <p>
 * With a pot set on the sheet, each member of a balanced pool has a cut: the pot times their share
 * divided by 100, rounded half to even to two decimals, a retired member's 0.00. The remainder, the
 * pot less the cuts added up, goes to the member with the largest share (the earliest added on a
 * tie), so the cuts total exactly the pot. An unbalanced pool, or no pot, has no cuts.
 */
@Service
public class PoolService {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    /**
     * A member as listed: the person's id and current name, their share, whether they are retired, and
     * their cut of the pot (two decimals; blank when the pool is unbalanced or there is no pot).
     */
    public record MemberView(long personId, String name, String share, boolean retired, String cut) {
    }

    /** A pool as listed: its members in the order they were added, their total share (two decimals) and balance. */
    public record PoolView(long id, String name, List<MemberView> members, String totalShare, boolean balanced) {
    }

    private final PoolRepository pools;
    private final PoolMemberRepository members;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Audit audit;
    private final RoleService roles;

    public PoolService(PoolRepository pools, PoolMemberRepository members, PersonRepository people,
            SheetService sheets, SheetRepository sheetRows, Audit audit, RoleService roles) {
        this.pools = pools;
        this.members = members;
        this.people = people;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.audit = audit;
        this.roles = roles;
    }

    @Transactional(readOnly = true)
    public List<PoolView> list(String sheetId) {
        List<Pool> all = pools.findBySheetIdOrderByIdAsc(sheetId);
        if (all.isEmpty()) {
            return List.of();
        }
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, List<PoolMember>> byPool = members
                .findByPoolIdInOrderByIdAsc(all.stream().map(Pool::getId).toList()).stream()
                .collect(Collectors.groupingBy(PoolMember::getPoolId));
        BigDecimal pot = sheetRows.findById(sheetId).map(s -> s.getPot()).orElse(null);
        List<PoolView> out = new ArrayList<>();
        for (Pool pool : all) {
            out.add(view(pool, byPool.getOrDefault(pool.getId(), List.of()), byId, pot));
        }
        return out;
    }

    private static PoolView view(Pool pool, List<PoolMember> poolMembers, Map<Long, Person> byId, BigDecimal pot) {
        List<Person> listed = new ArrayList<>();
        List<BigDecimal> shares = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (PoolMember m : poolMembers) {
            Person p = byId.get(m.getPersonId());
            if (p == null) {
                continue;
            }
            BigDecimal share = m.getShare().setScale(2);
            listed.add(p);
            shares.add(share);
            if (!p.isRetired()) {
                total = total.add(share);
            }
        }
        boolean balanced = total.compareTo(HUNDRED) == 0;
        List<BigDecimal> cuts = balanced && pot != null ? cuts(pot, listed, shares) : null;
        List<MemberView> views = new ArrayList<>();
        for (int i = 0; i < listed.size(); i++) {
            Person p = listed.get(i);
            views.add(new MemberView(p.getId(), p.getName(), shares.get(i).toPlainString(), p.isRetired(),
                    cuts == null ? "" : cuts.get(i).toPlainString()));
        }
        return new PoolView(pool.getId(), pool.getName(), views, total.toPlainString(), balanced);
    }

    /**
     * Each member's cut of the pot, in the members' order: the pot times their share over 100, half to
     * even, a retired member's 0.00; the remainder to the live member with the largest share, the
     * earliest on a tie.
     */
    private static List<BigDecimal> cuts(BigDecimal pot, List<Person> listed, List<BigDecimal> shares) {
        List<BigDecimal> cuts = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO.setScale(2);
        int largest = -1;
        for (int i = 0; i < listed.size(); i++) {
            BigDecimal cut = BigDecimal.ZERO.setScale(2);
            if (!listed.get(i).isRetired()) {
                cut = pot.multiply(shares.get(i)).divide(HUNDRED, 2, RoundingMode.HALF_EVEN);
                if (largest < 0 || shares.get(i).compareTo(shares.get(largest)) > 0) {
                    largest = i;
                }
            }
            cuts.add(cut);
            sum = sum.add(cut);
        }
        if (largest >= 0) {
            cuts.set(largest, cuts.get(largest).add(pot.subtract(sum)));
        }
        return cuts;
    }

    /** Add a pool (trimmed); a blank name is refused (422), one already on the sheet (409). */
    @Transactional
    public void add(String sheetId, String user, String name) {
        roles.refuseViewer(sheetId, user);
        sheets.load(sheetId, user);
        String text = name == null ? "" : name.trim();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Enter a pool name.");
        }
        // hold the sheet so the same name added twice at the same moment is checked one after the other
        sheetRows.findLockedById(sheetId);
        if (pools.existsBySheetIdAndName(sheetId, text)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That pool is already on the sheet.");
        }
        try {
            pools.saveAndFlush(new Pool(sheetId, text));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That pool is already on the sheet.");
        }
        audit.recordOnCommit("POOL_ADDED sheet=" + sheetId + " by=" + user + " name=" + text);
    }

    /**
     * Add a person to a pool with a share: refused (422) for a share that is not a number of at most
     * two decimals above 0.00 and at most 100.00, (409) for a person already in the pool.
     */
    @Transactional
    public void addMember(String sheetId, String user, long poolId, long personId, String share) {
        roles.refuseViewer(sheetId, user);
        sheets.load(sheetId, user);
        BigDecimal pct = share(share);
        sheetRows.findLockedById(sheetId);
        Pool pool = pools.findByIdAndSheetId(poolId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool."));
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose a person."));
        if (members.existsByPoolIdAndPersonId(pool.getId(), person.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That person is already in the pool.");
        }
        try {
            members.saveAndFlush(new PoolMember(pool.getId(), person.getId(), pct));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That person is already in the pool.");
        }
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " pool=" + pool.getName()
                + " name=" + person.getName() + " share=" + pct.toPlainString());
    }

    private static BigDecimal share(String text) {
        String s = text == null ? "" : text.trim();
        if (!s.matches("\\d{1,3}(\\.\\d{1,2})?")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Enter a share of at most two decimals.");
        }
        BigDecimal pct = new BigDecimal(s).setScale(2);
        if (pct.signum() <= 0 || pct.compareTo(HUNDRED) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "A share is more than 0.00 and at most 100.00.");
        }
        return pct;
    }
}
