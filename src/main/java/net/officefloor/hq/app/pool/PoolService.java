package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the pools of a sheet, each name at most once, and their members: each person at most once in a pool, with a
 * share percent above 0.00 and at most 100.00. A person may be in several pools. A pool may be a member of another
 * pool, of at most one, never containing itself through any chain, and a chain of pools is at most three deep.
 */
@Service
public class PoolService {

    static final BigDecimal MAX_SHARE = new BigDecimal("100.00");

    /** The most pools in a chain of pools, each a member of the next. */
    static final int MAX_DEPTH = 3;

    private final PoolRepository pools;
    private final PersonRepository people;
    private final SheetRepository sheets;
    private final Audit audit;

    public PoolService(PoolRepository pools, PersonRepository people, SheetRepository sheets, Audit audit) {
        this.pools = pools;
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Pool> list(String sheetId) {
        return pools.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a pool by its name (already trimmed, not blank); refused when the sheet has one by that name. */
    @Transactional
    public Pool add(String sheetId, String user, String name) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException(sheetId);
        }
        if (pools.existsBySheetIdAndName(sheetId, name)) {
            throw new PoolRefusedException(name + " is already a pool on sheet " + sheetId);
        }
        Pool saved;
        try {
            saved = pools.saveAndFlush(new Pool(sheetId, name));
        } catch (DataIntegrityViolationException e) {
            throw new PoolRefusedException(name + " is already a pool on sheet " + sheetId);
        }
        audit.recordOnCommit("POOL_ADDED sheet=" + sheetId + " by=" + user + " name=" + saved.getName());
        return saved;
    }

    /** Add the person to the pool with the share (two decimals); refused when already in it or the share is out of range. */
    @Transactional
    public Pool addMember(String sheetId, String user, Long poolId, Long personId, BigDecimal share) {
        Pool pool = pools.findByIdAndSheetId(poolId, sheetId)
                .orElseThrow(() -> new PoolRefusedException("No pool " + poolId + " on sheet " + sheetId));
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, personId));
        if (share.scale() > 2 || share.signum() <= 0 || share.compareTo(MAX_SHARE) > 0) {
            throw new PoolRefusedException("A share is more than 0.00 and at most 100.00, with two decimals");
        }
        if (pool.hasMember(personId)) {
            throw new PoolRefusedException(person.getName() + " is already in pool " + pool.getName());
        }
        BigDecimal scaled = share.setScale(2);
        pool.getMembers().add(new PoolMember(personId, scaled));
        try {
            pools.saveAndFlush(pool);
        } catch (DataIntegrityViolationException e) {
            throw new PoolRefusedException(person.getName() + " is already in pool " + pool.getName());
        }
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " pool=" + pool.getName()
                + " name=" + person.getName() + " share=" + scaled.toPlainString());
        return pool;
    }

    /**
     * Add another pool of the sheet to the pool as a member with the share (two decimals); refused when the share is out
     * of range, when the member is already in a pool, when the pool would then contain itself through any chain, or
     * when a chain of pools would then be more than three deep.
     */
    @Transactional
    public Pool addMemberPool(String sheetId, String user, Long poolId, Long memberPoolId, BigDecimal share) {
        Pool pool = pools.findByIdAndSheetId(poolId, sheetId)
                .orElseThrow(() -> new PoolRefusedException("No pool " + poolId + " on sheet " + sheetId));
        Pool member = pools.findByIdAndSheetId(memberPoolId, sheetId)
                .orElseThrow(() -> new PoolRefusedException("No pool " + memberPoolId + " on sheet " + sheetId));
        if (share.scale() > 2 || share.signum() <= 0 || share.compareTo(MAX_SHARE) > 0) {
            throw new PoolRefusedException("A share is more than 0.00 and at most 100.00, with two decimals");
        }
        List<Pool> all = pools.findBySheetIdOrderByIdAsc(sheetId);
        // Each pool that is a member of another, by the pool above it.
        Map<Long, Long> above = new HashMap<>();
        for (Pool p : all) {
            for (PoolMember m : p.getMembers()) {
                if (m.getMemberPoolId() != null) {
                    above.put(m.getMemberPoolId(), p.getId());
                }
            }
        }
        if (above.containsKey(memberPoolId)) {
            throw new PoolRefusedException(member.getName() + " is already a member of a pool");
        }
        // Levels from the top down to the pool (the pool itself counting one); a chain from the member back to the
        // pool would contain the pool in itself.
        int levels = 0;
        for (Long at = poolId; at != null; at = above.get(at)) {
            if (at.equals(memberPoolId)) {
                throw new PoolRefusedException(pool.getName() + " may not contain itself");
            }
            levels++;
        }
        if (levels + height(memberPoolId, all) > MAX_DEPTH) {
            throw new PoolRefusedException("A chain of pools is at most " + MAX_DEPTH + " deep");
        }
        BigDecimal scaled = share.setScale(2);
        pool.getMembers().add(PoolMember.ofPool(memberPoolId, scaled));
        try {
            pools.saveAndFlush(pool);
        } catch (DataIntegrityViolationException e) {
            throw new PoolRefusedException(member.getName() + " is already a member of a pool");
        }
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " pool=" + pool.getName()
                + " name=" + member.getName() + " share=" + scaled.toPlainString());
        return pool;
    }

    /** The levels of pools from this one down its deepest chain of member pools, itself counting one. */
    private static int height(Long poolId, List<Pool> all) {
        Pool pool = all.stream().filter(p -> p.getId().equals(poolId)).findFirst().orElse(null);
        int below = 0;
        if (pool != null) {
            for (PoolMember m : pool.getMembers()) {
                if (m.getMemberPoolId() != null) {
                    below = Math.max(below, height(m.getMemberPoolId(), all));
                }
            }
        }
        return 1 + below;
    }
}
