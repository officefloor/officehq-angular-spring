package net.officefloor.hq.app.people;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersonService {

    private final PersonRepository people;

    private final Audit audit;

    public PersonService(PersonRepository people, Audit audit) {
        this.people = people;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Person> list() {
        return people.findAll(Sort.by("id"));
    }

    @Transactional(readOnly = true)
    public Optional<Person> find(long id) {
        return people.findById(id);
    }

    @Transactional
    public Person add(String name, Integer alpha, Integer beta, Integer gamma) {
        return people.save(new Person(name.trim(), alpha, beta, gamma));
    }

    @Transactional
    /** Sets alpha; a real change is recorded in the audit as ALPHA_CHANGED id=.. from=.. to=... */
    public Optional<Person> setAlpha(long id, Integer alpha) {
        return people.findById(id).map(p -> {
            Integer previous = p.getAlpha();
            p.setAlpha(alpha);
            if (!Objects.equals(previous, alpha)) {
                audit.record("ALPHA_CHANGED id=" + id + " from=" + previous + " to=" + alpha);
            }
            return p;
        });
    }

    @Transactional
    public Optional<Person> setBeta(long id, Integer beta) {
        return people.findById(id).map(p -> {
            p.setBeta(beta);
            return p;
        });
    }

    @Transactional
    public Optional<Person> setGamma(long id, Integer gamma) {
        return people.findById(id).map(p -> {
            p.setGamma(gamma);
            return p;
        });
    }

    /** Sets the patron (another person) by id; null clears it. Refuses oneself or an unknown person. */
    @Transactional
    public Optional<Person> setPatron(long id, Long patronId) {
        return people.findById(id).map(p -> {
            if (patronId == null) {
                p.setPatron(null);
            } else {
                if (patronId == id) {
                    throw new IllegalArgumentException("a person cannot be their own patron");
                }
                p.setPatron(people.findById(patronId)
                        .orElseThrow(() -> new IllegalArgumentException("no person " + patronId)));
            }
            return p;
        });
    }
}
