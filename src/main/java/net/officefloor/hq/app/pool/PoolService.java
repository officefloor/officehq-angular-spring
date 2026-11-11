package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.util.List;
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
 * share percent above 0.00 and at most 100.00. A person may be in several pools.
 */
@Service
public class PoolService {

    static final BigDecimal MAX_SHARE = new BigDecimal("100.00");

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
}
