package net.officefloor.hq.app.people;

import java.util.List;
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
        return people.findAllByOrderByIdAsc().stream().map(PersonView::of).toList();
    }
}
