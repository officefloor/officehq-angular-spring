package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps each sheet's pools and their members. */
@Service
public class PoolService {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");
    private static final int MAX_DEPTH = 3;

    private final PoolRepository pools;
    private final PersonRepository people;
    private final SheetService sheets;
    private final Audit audit;

    public PoolService(PoolRepository pools, PersonRepository people, SheetService sheets, Audit audit) {
        this.pools = pools;
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's pools, in the order they were added. */
    @Transactional(readOnly = true)
    public List<Pool> list(String sheetId) {
        return pools.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a pool by a name (surrounding spaces removed); a blank name, or one already taken, is refused. */
    @Transactional
    public Pool add(String sheetId, String user, String name) {
        sheets.today(sheetId);
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw new PoolRefusedException("Enter a pool name.");
        }
        if (pools.existsBySheetIdAndName(sheetId, n)) {
            throw new PoolRefusedException("\"" + n + "\" is already a pool.");
        }
        Pool saved = pools.saveAndFlush(new Pool(sheetId, n));
        audit.recordOnCommit("POOL_ADDED sheet=" + sheetId + " by=" + user + " name=" + n);
        return saved;
    }

    /** Add a member to a pool with a share percent: a person of the sheet, or another of its pools (poolId).
     *  Refused: a share not more than 0.00 and at most 100.00 in two decimals; a person already in it; a pool
     *  that would contain itself through any chain, that is already a member of a pool, or that would make a
     *  chain of pools more than three deep. */
    @Transactional
    public Pool addMember(String sheetId, String user, long poolId, Long personId, Long memberPoolId,
            BigDecimal share) {
        Pool pool = pools.findByIdAndSheetId(poolId, sheetId)
                .orElseThrow(() -> new PoolRefusedException("No such pool."));
        Person person = personId == null ? null : people.findByIdAndSheetId(personId, sheetId).orElse(null);
        Pool member = memberPoolId == null ? null : pools.findByIdAndSheetId(memberPoolId, sheetId).orElse(null);
        if ((person == null) == (member == null)) {
            throw new PoolRefusedException("Choose a person or a pool.");
        }
        if (share == null || share.stripTrailingZeros().scale() > 2 || share.signum() <= 0
                || share.compareTo(HUNDRED) > 0) {
            throw new PoolRefusedException("Enter a share more than 0.00 and at most 100.00, in two decimals.");
        }
        BigDecimal s = share.setScale(2);
        String name;
        if (person != null) {
            if (pool.hasMember(person.getId())) {
                throw new PoolRefusedException(person.getName() + " is already in " + pool.getName() + ".");
            }
            pool.addMember(person.getId(), s);
            name = person.getName();
        } else {
            refuseMemberPool(sheetId, pool, member);
            pool.addMemberPool(member.getId(), s);
            name = member.getName();
        }
        Pool saved = pools.saveAndFlush(pool);
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " pool=" + pool.getName()
                + " name=" + name + " share=" + s.toPlainString());
        return saved;
    }

    /** Refuse making a pool a member of another when it would contain itself, is already a member of a pool,
     *  or the chain of pools would be more than three deep. */
    private void refuseMemberPool(String sheetId, Pool pool, Pool member) {
        List<Pool> all = pools.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, Long> parentOf = new HashMap<>();
        for (Pool p : all) {
            for (PoolMember m : p.getMembers()) {
                if (m.getMemberPoolId() != null) {
                    parentOf.put(m.getMemberPoolId(), p.getId());
                }
            }
        }
        int levelsAbove = 0;
        for (Long at = pool.getId(); at != null; at = parentOf.get(at)) {
            if (at.equals(member.getId())) {
                throw new PoolRefusedException(member.getName() + " cannot contain itself.");
            }
            levelsAbove++;
        }
        Long above = parentOf.get(member.getId());
        if (above != null) {
            String in = all.stream().filter(p -> p.getId().equals(above)).map(Pool::getName).findFirst().orElse("");
            throw new PoolRefusedException(member.getName() + " is already in " + in + ".");
        }
        if (levelsAbove + depth(member, all) > MAX_DEPTH) {
            throw new PoolRefusedException("A chain of pools is at most " + MAX_DEPTH + " deep.");
        }
    }

    /** How many levels of pools a pool heads: 1, and the deepest of the pools in it. */
    private static int depth(Pool pool, List<Pool> all) {
        int below = 0;
        for (PoolMember m : pool.getMembers()) {
            if (m.getMemberPoolId() != null) {
                Pool child = all.stream().filter(p -> p.getId().equals(m.getMemberPoolId())).findFirst().orElse(null);
                if (child != null) {
                    below = Math.max(below, depth(child, all));
                }
            }
        }
        return 1 + below;
    }
}
