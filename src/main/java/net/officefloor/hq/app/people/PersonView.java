package net.officefloor.hq.app.people;

import java.util.List;

/** A person as the API returns it; patrons are referenced by id. */
public record PersonView(Long id, String name, Integer alpha, Integer beta, Integer gamma, boolean hold,
        List<Long> patronIds) {

    static PersonView of(Person p) {
        return new PersonView(p.getId(), p.getName(), p.getAlpha(), p.getBeta(), p.getGamma(), p.isHold(),
                p.getPatrons().stream().map(Person::getId).sorted().toList());
    }
}
