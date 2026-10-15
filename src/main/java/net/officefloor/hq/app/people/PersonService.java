package net.officefloor.hq.app.people;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersonService {

    private final PersonRepository people;

    private final SheetRepository sheets;

    private final UnnamedSheetRepository unnamedSheet;

    private final PersonGroupRepository groups;

    private final Audit audit;

    public PersonService(PersonRepository people, SheetRepository sheets, UnnamedSheetRepository unnamedSheet,
            PersonGroupRepository groups, Audit audit) {
        this.people = people;
        this.groups = groups;
        this.sheets = sheets;
        this.unnamedSheet = unnamedSheet;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    /** The people on a sheet; a null sheet id is the unnamed sheet. */
    public List<Person> list(String sheetId) {
        return people.findBySheetId(sheetId, Sort.by("localId"));
    }

    /** Whether a named sheet has been stored. */
    @Transactional(readOnly = true)
    public boolean sheetExists(String sheetId) {
        return sheets.existsById(sheetId);
    }

    /**
     * A sheet's anchor date: the one set on it, or today when none is set; a null sheet id is the
     * unnamed sheet.
     */
    @Transactional(readOnly = true)
    public LocalDate anchor(String sheetId) {
        Optional<LocalDate> anchor = sheetId == null
                ? unnamedSheet.findById(UnnamedSheet.ID).map(UnnamedSheet::getAnchor)
                : sheets.findById(sheetId).map(Sheet::getAnchor);
        return anchor.orElseGet(LocalDate::now);
    }

    /** Opens a named sheet, storing it as a new empty sheet when it does not exist yet. */
    @Transactional
    public Sheet openSheet(String sheetId) {
        return sheets.findById(sheetId).orElseGet(() -> sheets.save(new Sheet(sheetId)));
    }

    /** The person with an id on a sheet; a null sheet id is the unnamed sheet. */
    @Transactional(readOnly = true)
    public Optional<Person> find(String sheetId, long id) {
        return people.findBySheetIdAndLocalId(sheetId, id);
    }

    @Transactional
    /** Adds a person to a sheet (null for the unnamed sheet), creating the sheet on first use. */
    public Person add(String sheetId, String name, Integer alpha, Integer beta, Integer gamma, Integer delta, Integer epsilon, Integer zeta,
            Grade grade, Kind kind, Collection<String> regions, Collection<Long> groupIds) {
        ensureSheet(sheetId);
        List<PersonGroup> memberOf = groupIds.stream().map(g -> group(sheetId, g)).toList();
        long id = people.maxLocalId(sheetId) + 1;
        Person person = new Person(sheetId, id, name.trim(), alpha, beta, gamma);
        person.setDelta(delta);
        person.setEpsilon(epsilon);
        person.setZeta(zeta);
        person.setGrade(grade);
        person.setKind(kind);
        person.setRegions(regions);
        person.setGroups(memberOf);
        return people.save(person);
    }

    /** The groups on a sheet, by id; a null sheet id is the unnamed sheet. */
    @Transactional(readOnly = true)
    public List<PersonGroup> groups(String sheetId) {
        return groups.findBySheetId(sheetId, Sort.by("localId"));
    }

    /** Adds a named group to a sheet (null for the unnamed sheet), creating the sheet on first use. */
    @Transactional
    public PersonGroup addGroup(String sheetId, String name) {
        ensureSheet(sheetId);
        return groups.save(new PersonGroup(sheetId, groups.maxLocalId(sheetId) + 1, name.trim()));
    }

    /** Puts the person in just the groups with these ids on the same sheet; none takes them out of every group. Refuses an unknown group. */
    @Transactional
    public Optional<Person> setGroups(String sheetId, long id, Collection<Long> groupIds) {
        return people.findBySheetIdAndLocalId(sheetId, id).map(p -> {
            p.setGroups(groupIds.stream().map(g -> group(sheetId, g)).toList());
            return p;
        });
    }

    private PersonGroup group(String sheetId, Long groupId) {
        return groups.findBySheetIdAndLocalId(sheetId, groupId)
                .orElseThrow(() -> new IllegalArgumentException("no group " + groupId + " on this sheet"));
    }

    private void ensureSheet(String sheetId) {
        if (sheetId != null && !sheets.existsById(sheetId)) {
            sheets.save(new Sheet(sheetId));
        }
    }

    @Transactional
    /** Sets alpha; a real change is recorded in the audit as ALPHA_CHANGED id=.. from=.. to=... */
    public Optional<Person> setAlpha(String sheetId, long id, Integer alpha) {
        return people.findBySheetIdAndLocalId(sheetId, id).map(p -> {
            Integer previous = p.getAlpha();
            p.setAlpha(alpha);
            if (!Objects.equals(previous, alpha)) {
                audit.record("ALPHA_CHANGED id=" + id + " from=" + previous + " to=" + alpha);
            }
            return p;
        });
    }

    @Transactional
    public Optional<Person> setBeta(String sheetId, long id, Integer beta) {
        return people.findBySheetIdAndLocalId(sheetId, id).map(p -> {
            p.setBeta(beta);
            return p;
        });
    }

    @Transactional
    public Optional<Person> setGamma(String sheetId, long id, Integer gamma) {
        return people.findBySheetIdAndLocalId(sheetId, id).map(p -> {
            p.setGamma(gamma);
            return p;
        });
    }

    @Transactional
    public Optional<Person> setDelta(String sheetId, long id, Integer delta) {
        return people.findBySheetIdAndLocalId(sheetId, id).map(p -> {
            p.setDelta(delta);
            return p;
        });
    }

    @Transactional
    public Optional<Person> setEpsilon(String sheetId, long id, Integer epsilon) {
        return people.findBySheetIdAndLocalId(sheetId, id).map(p -> {
            p.setEpsilon(epsilon);
            return p;
        });
    }

    @Transactional
    public Optional<Person> setZeta(String sheetId, long id, Integer zeta) {
        return people.findBySheetIdAndLocalId(sheetId, id).map(p -> {
            p.setZeta(zeta);
            return p;
        });
    }

    @Transactional
    public Optional<Person> setGrade(String sheetId, long id, Grade grade) {
        return people.findBySheetIdAndLocalId(sheetId, id).map(p -> {
            p.setGrade(grade);
            return p;
        });
    }

    @Transactional
    public Optional<Person> setKind(String sheetId, long id, Kind kind) {
        return people.findBySheetIdAndLocalId(sheetId, id).map(p -> {
            p.setKind(kind);
            return p;
        });
    }

    @Transactional
    public Optional<Person> setRegions(String sheetId, long id, Collection<String> regions) {
        return people.findBySheetIdAndLocalId(sheetId, id).map(p -> {
            p.setRegions(regions);
            return p;
        });
    }

    @Transactional
    public Optional<Person> setActive(String sheetId, long id, boolean active) {
        return people.findBySheetIdAndLocalId(sheetId, id).map(p -> {
            p.setActive(active);
            return p;
        });
    }

    /** Moves the person forward to their next state (REWORK back to FINAL), recorded in the audit as STATE_CHANGED id=.. from=.. to=...; refused once FINAL. */
    @Transactional
    public Optional<Person> advance(String sheetId, long id) {
        return people.findBySheetIdAndLocalId(sheetId, id).map(p -> {
            State previous = p.getState();
            State next = previous.next();
            if (next == null) {
                throw new IllegalStateException("a person at " + previous + " cannot move forward");
            }
            p.setState(next);
            audit.record("STATE_CHANGED id=" + id + " from=" + previous + " to=" + next);
            return p;
        });
    }

    /** Reopens a FINAL person to REWORK, recorded in the audit like {@link #advance}; refused unless FINAL. */
    @Transactional
    public Optional<Person> rework(String sheetId, long id) {
        return people.findBySheetIdAndLocalId(sheetId, id).map(p -> {
            State current = p.getState();
            State reopened = current.reopened();
            if (reopened == null) {
                throw new IllegalStateException("a person at " + current + " cannot be reworked");
            }
            p.setState(reopened);
            audit.record("STATE_CHANGED id=" + id + " from=" + current + " to=" + reopened);
            return p;
        });
    }

    /** Moves the person back to their previous state, recorded in the audit like {@link #advance}; refused once FINAL, at REWORK or at DRAFT. */
    @Transactional
    public Optional<Person> demote(String sheetId, long id) {
        return people.findBySheetIdAndLocalId(sheetId, id).map(p -> {
            State current = p.getState();
            State previous = current.previous();
            if (previous == null) {
                throw new IllegalStateException("a person at " + current + " cannot move back");
            }
            p.setState(previous);
            audit.record("STATE_CHANGED id=" + id + " from=" + current + " to=" + previous);
            return p;
        });
    }

    /** Sets the person's whole-number score; null clears it. */
    @Transactional
    public Optional<Person> setScore(String sheetId, long id, Integer score) {
        return people.findBySheetIdAndLocalId(sheetId, id).map(p -> {
            p.setScore(score);
            return p;
        });
    }

    /** Sets the patrons (other people on the same sheet) by their id on that sheet; an empty list clears them. Refuses oneself or an unknown person. */
    @Transactional
    public Optional<Person> setPatrons(String sheetId, long id, Collection<Long> patronIds) {
        return people.findBySheetIdAndLocalId(sheetId, id).map(p -> {
            Set<Person> patrons = new HashSet<>();
            for (Long patronId : patronIds) {
                if (patronId == id) {
                    throw new IllegalArgumentException("a person cannot be their own patron");
                }
                patrons.add(people.findBySheetIdAndLocalId(sheetId, patronId)
                        .orElseThrow(() -> new IllegalArgumentException("no person " + patronId + " on this sheet")));
            }
            p.setPatrons(patrons);
            return p;
        });
    }

    /** Sets the allies (the other people on the same sheet this person follows) by their id on that sheet; an empty list clears them. Following is one-directional. Refuses oneself or an unknown person. */
    @Transactional
    public Optional<Person> setAllies(String sheetId, long id, Collection<Long> allyIds) {
        return people.findBySheetIdAndLocalId(sheetId, id).map(p -> {
            Set<Person> allies = new HashSet<>();
            for (Long allyId : allyIds) {
                if (allyId == id) {
                    throw new IllegalArgumentException("a person cannot be their own ally");
                }
                allies.add(people.findBySheetIdAndLocalId(sheetId, allyId)
                        .orElseThrow(() -> new IllegalArgumentException("no person " + allyId + " on this sheet")));
            }
            p.setAllies(allies);
            return p;
        });
    }
}
