package net.officefloor.hq.app.people;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersonService {

    private final PersonRepository people;

    public PersonService(PersonRepository people) {
        this.people = people;
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
    public Optional<Person> setAlpha(long id, Integer alpha) {
        return people.findById(id).map(p -> {
            p.setAlpha(alpha);
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
}
