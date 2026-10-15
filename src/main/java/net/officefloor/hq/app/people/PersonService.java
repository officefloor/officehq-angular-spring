package net.officefloor.hq.app.people;

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

    private final Audit audit;

    public PersonService(PersonRepository people, SheetRepository sheets, Audit audit) {
        this.people = people;
        this.sheets = sheets;
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
    public Person add(String sheetId, String name, Integer alpha, Integer beta, Integer gamma) {
        if (sheetId != null && !sheets.existsById(sheetId)) {
            sheets.save(new Sheet(sheetId));
        }
        long id = people.maxLocalId(sheetId) + 1;
        return people.save(new Person(sheetId, id, name.trim(), alpha, beta, gamma));
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
}
