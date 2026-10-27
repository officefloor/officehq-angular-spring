package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/pools")
public class PoolController {

    private static final BigDecimal BALANCED = new BigDecimal("100.00");

    /**
     * A member of a pool: the person (or, for a pool that is a member, that pool and a null person), the name, the
     * share, and whether a person member is retired (so their share counts 0.00); a pool member is never retired.
     */
    public record MemberView(Long personId, Long poolId, String name, String share, boolean retired) {
    }

    /** A pool, its members, and its total share, a retired member's counting 0.00; balanced at exactly 100.00. */
    public record PoolView(Long id, String name, List<MemberView> members, String totalShare, boolean balanced) {
    }

    public record PoolForm(@NotNull @Size(max = 140) String name) {
    }

    /** A member to add: a person, or (with no person) another pool. */
    public record MemberForm(Long personId, Long poolId, @NotNull BigDecimal share) {
    }

    private final PoolService service;
    private final PersonRepository people;

    public PoolController(PoolService service, PersonRepository people) {
        this.service = service;
        this.people = people;
    }

    /** The sheet's pools, in the order they were added, each with its members in the order they were added. */
    @GetMapping
    @Transactional(readOnly = true)
    public List<PoolView> list(@PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, List<PoolMember>> byPool = service.members(sheetId).stream()
                .collect(Collectors.groupingBy(PoolMember::getPoolId));
        List<Pool> all = service.list(sheetId);
        Map<Long, String> names = all.stream().collect(Collectors.toMap(Pool::getId, Pool::getName));
        return all.stream().map(p -> view(p, byPool.getOrDefault(p.getId(), List.of()), byId, names)).toList();
    }

    /** Add a pool; a blank name, or one already a pool's, is refused. */
    @PostMapping
    public PoolView add(@PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody PoolForm form, @SessionAttribute(SessionController.USER) String user) {
        return view(service.add(user, sheetId, form.name()), List.of(), Map.of(), Map.of());
    }

    /** Add a person, or another pool, to a pool with a share; the pool as it now stands. */
    @PostMapping("/{poolId}/members")
    @Transactional
    public PoolView addMember(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long poolId, @Valid @RequestBody MemberForm form,
            @SessionAttribute(SessionController.USER) String user) {
        if (form.personId() != null) {
            service.addMember(user, sheetId, poolId, form.personId(), form.share());
        } else if (form.poolId() != null) {
            service.addPoolMember(user, sheetId, poolId, form.poolId(), form.share());
        } else {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Choose a member.");
        }
        return list(sheetId).stream().filter(p -> p.id().equals(poolId)).findFirst().orElseThrow();
    }

    private static PoolView view(Pool pool, List<PoolMember> members, Map<Long, Person> byId,
            Map<Long, String> poolNames) {
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        List<MemberView> views = new ArrayList<>();
        for (PoolMember m : members) {
            if (m.getMemberPoolId() != null) {
                total = total.add(m.getShare());
                views.add(new MemberView(null, m.getMemberPoolId(), poolNames.getOrDefault(m.getMemberPoolId(), ""),
                        m.getShare().setScale(2).toPlainString(), false));
                continue;
            }
            Person person = byId.get(m.getPersonId());
            boolean retired = person == null || person.getRetiredFrom() != null;
            if (!retired) {
                total = total.add(m.getShare());
            }
            views.add(new MemberView(m.getPersonId(), null, person == null ? "" : person.getName(),
                    m.getShare().setScale(2).toPlainString(), retired));
        }
        return new PoolView(pool.getId(), pool.getName(), views, total.toPlainString(),
                total.compareTo(BALANCED) == 0);
    }
}
