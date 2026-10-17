package net.officefloor.hq.app.people;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PeopleService {

    private final PersonRepository people;

    public PeopleService(PersonRepository people) {
        this.people = people;
    }

    @Transactional(readOnly = true)
    public List<PersonView> list() {
        List<Person> all = people.findAllByOrderByIdAsc();
        Long averageScaled = PersonView.averageScaled(all);
        Map<Long, Set<Person>> patrons = PersonView.mutualPatrons(all);
        return all.stream().map(p -> PersonView.of(p, patrons.get(p.getId()), averageScaled)).toList();
    }
}
