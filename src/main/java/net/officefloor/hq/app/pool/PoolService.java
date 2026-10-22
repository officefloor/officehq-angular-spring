package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The pools on a sheet: listed with their members, added, and given members. */
@Service
public class PoolService {

    private static final BigDecimal MAX_SHARE = new BigDecimal("100.00");

    /** A pool with its members in the order they were added, each with the person's name. */
    public record PoolWithMembers(Pool pool, List<Member> members) {
    }

    public record Member(PoolMember member, String name) {
    }

    private final PoolRepository pools;
    private final PoolMemberRepository members;
    private final PersonRepository people;
    private final SheetService sheets;
    private final Audit audit;

    public PoolService(PoolRepository pools, PoolMemberRepository members, PersonRepository people,
            SheetService sheets, Audit audit) {
        this.pools = pools;
        this.members = members;
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's pools in the order they were added, with their members. */
    @Transactional(readOnly = true)
    public List<PoolWithMembers> list(String sheetId) {
        List<Pool> all = pools.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, String> names = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Person::getName));
        Map<Long, List<Member>> byPool = members.findByPoolIdInOrderByIdAsc(all.stream().map(Pool::getId).toList())
                .stream().map(m -> new Member(m, names.get(m.getPersonId())))
                .collect(Collectors.groupingBy(m -> m.member().getPoolId()));
        return all.stream().map(p -> new PoolWithMembers(p, byPool.getOrDefault(p.getId(), List.of()))).toList();
    }

    /** Add a pool; refused when its name, trimmed, is blank or already a pool's on the sheet. */
    @Transactional
    public PoolWithMembers add(String sheetId, String user, String name) {
        String trimmed = name.strip();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A pool needs a name.");
        }
        sheets.lock(sheetId);
        if (pools.existsBySheetIdAndName(sheetId, trimmed)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "There is already a pool by that name.");
        }
        Pool saved = pools.saveAndFlush(new Pool(sheetId, trimmed));
        audit.recordOnCommit("POOL_ADDED sheet=" + sheetId + " by=" + user + " name=" + trimmed);
        return new PoolWithMembers(saved, List.of());
    }

    /**
     * Add a person on the sheet to a pool with a share percent; refused when the share is not more than
     * 0.00 and at most 100.00 with two decimals, or the person is already in the pool.
     */
    @Transactional
    public PoolWithMembers addMember(String sheetId, String user, long poolId, long personId, BigDecimal share) {
        if (share.scale() > 2 || share.signum() <= 0 || share.compareTo(MAX_SHARE) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A share is more than 0.00 and at most 100.00, with two decimals.");
        }
        sheets.lock(sheetId);
        Pool pool = pools.findBySheetIdAndId(sheetId, poolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool."));
        Person person = people.findById(personId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (members.existsByPoolIdAndPersonId(poolId, personId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That person is already in the pool.");
        }
        BigDecimal scaled = share.setScale(2, RoundingMode.UNNECESSARY);
        members.saveAndFlush(new PoolMember(poolId, personId, scaled));
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " pool=" + pool.getName()
                + " name=" + person.getName() + " share=" + scaled.toPlainString());
        return list(sheetId).stream().filter(p -> p.pool().getId().equals(poolId)).findFirst().orElseThrow();
    }
}
