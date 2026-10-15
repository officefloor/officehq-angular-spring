package net.officefloor.hq.app.people;

import java.util.List;
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

    @Transactional
    public Person add(String name, int alpha) {
        return people.save(new Person(name.trim(), alpha));
    }
}
