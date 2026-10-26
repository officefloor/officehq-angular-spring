package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.crew.CrewRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PoolService {

    /** A share percent as typed: up to two decimals. */
    private static final Pattern SHARE = Pattern.compile("\\d{1,3}(\\.\\d{1,2})?");
    private static final BigDecimal HUNDRED = new BigDecimal("100.00");
    /** How many pools deep a chain of pools may go. */
    private static final int MAX_DEPTH = 3;

    private final PoolRepository pools;
    private final CrewRepository crews;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Audit audit;

    public PoolService(PoolRepository pools, CrewRepository crews, PersonRepository people, SheetService sheets, SheetRepository sheetRows,
            Audit audit) {
        this.pools = pools;
        this.crews = crews;
        this.people = people;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.audit = audit;
    }

    /** The sheet's pools in the order they were added, each with its members. */
    @Transactional(readOnly = true)
    public List<Pool> list(String sheetId) {
        return pools.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a pool under its name (trimmed), refused when it is blank or the sheet has a pool or a crew (which is
     *  also a pool) of that name. */
    @Transactional
    public Pool add(String sheetId, String user, String name) {
        String key = name == null ? "" : name.trim();
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Type a pool name.");
        }
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        if (pools.existsBySheetIdAndName(sheetId, key)) throw alreadyPool(key);
        if (crews.existsBySheetIdAndName(sheetId, key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The sheet already has a crew \"" + key + "\".");
        }
        Pool saved;
        try {
            saved = pools.saveAndFlush(new Pool(sheetId, key));
        } catch (DataIntegrityViolationException raced) {
            throw alreadyPool(key);
        }
        audit.recordOnCommit("POOL_ADDED sheet=" + sheetId + " by=" + user + " name=" + saved.getName());
        return saved;
    }

    /** Add a person of the sheet to a pool with a share percent of up to two decimals, more than 0.00
     *  and at most 100.00, from a from-date through a to-date (either optional); refused when the person is
     *  already in the pool or the to-date is before the from-date. */
    @Transactional
    public Pool addMember(String sheetId, String user, long poolId, long personId, String share, LocalDate from,
            LocalDate to) {
        BigDecimal value = shareOf(share);
        checkDates(from, to);
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        Pool pool = poolOf(sheetId, poolId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Choose a person of the sheet."));
        if (pool.hasMember(personId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    person.getName() + " is already in the pool " + pool.getName() + ".");
        }
        pool.addMember(personId, value, from, to);
        pool = pools.saveAndFlush(pool);
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " pool=" + pool.getName()
                + " name=" + person.getName() + " share=" + value.toPlainString() + datesOf(from, to));
        return pool;
    }

    /** Add another pool of the sheet to a pool with a share and dates as for a person; refused when it is the pool
     *  itself, is already a member of a pool, contains the pool through any chain, or would make a chain of
     *  pools more than {@value #MAX_DEPTH} deep. */
    @Transactional
    public Pool addMemberPool(String sheetId, String user, long poolId, long memberPoolId, String share,
            LocalDate from, LocalDate to) {
        BigDecimal value = shareOf(share);
        checkDates(from, to);
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        Pool pool = poolOf(sheetId, poolId);
        Pool member = pools.findById(memberPoolId).filter((p) -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Choose a pool of the sheet."));
        List<Pool> all = pools.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, Pool> parentOf = new HashMap<>();
        for (Pool p : all) {
            for (PoolMember m : p.getMembers()) {
                if (m.getMemberPoolId() != null) parentOf.put(m.getMemberPoolId(), p);
            }
        }
        if (member.getId().equals(pool.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A pool cannot be a member of itself.");
        }
        Pool parent = parentOf.get(member.getId());
        if (parent != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The pool " + member.getName() + " is already a member of the pool " + parent.getName() + ".");
        }
        int above = 1;
        for (Pool p = pool; parentOf.containsKey(p.getId()); p = parentOf.get(p.getId())) {
            if (parentOf.get(p.getId()).getId().equals(member.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "The pool " + member.getName() + " contains the pool " + pool.getName() + ".");
            }
            above++;
        }
        Map<Long, Pool> byId = new HashMap<>();
        all.forEach((p) -> byId.put(p.getId(), p));
        if (above + depthOf(member, byId) > MAX_DEPTH) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A chain of pools may be at most " + MAX_DEPTH + " deep.");
        }
        pool.addMemberPool(member.getId(), value, from, to);
        try {
            pool = pools.saveAndFlush(pool);
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The pool " + member.getName() + " is already a member of a pool.");
        }
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " pool=" + pool.getName()
                + " name=" + member.getName() + " share=" + value.toPlainString() + datesOf(from, to));
        return pool;
    }

    /** How many pools deep the pool's chains go: 1 with no member pools. */
    private static int depthOf(Pool pool, Map<Long, Pool> byId) {
        int deepest = 0;
        for (PoolMember m : pool.getMembers()) {
            if (m.getMemberPoolId() != null && byId.containsKey(m.getMemberPoolId())) {
                deepest = Math.max(deepest, depthOf(byId.get(m.getMemberPoolId()), byId));
            }
        }
        return deepest + 1;
    }

    private Pool poolOf(String sheetId, long poolId) {
        return pools.findById(poolId).filter((p) -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool."));
    }

    /** A share as typed: up to two decimals, more than 0.00 and at most 100.00. */
    private static BigDecimal shareOf(String share) {
        String typed = share == null ? "" : share.trim();
        if (!SHARE.matcher(typed).matches() || new BigDecimal(typed).signum() <= 0
                || new BigDecimal(typed).compareTo(HUNDRED) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Type a share of more than 0.00 and at most 100.00, with up to two decimals.");
        }
        return new BigDecimal(typed).setScale(2);
    }

    /** Refuse a to-date before the from-date. */
    private static void checkDates(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "The to-date is before the from-date.");
        }
    }

    /** The membership's dates as the audit records them: from=<date> and to=<date>, each when given. */
    private static String datesOf(LocalDate from, LocalDate to) {
        return (from == null ? "" : " from=" + from) + (to == null ? "" : " to=" + to);
    }

    private static ResponseStatusException alreadyPool(String name) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "The sheet already has a pool \"" + name + "\".");
    }
}
