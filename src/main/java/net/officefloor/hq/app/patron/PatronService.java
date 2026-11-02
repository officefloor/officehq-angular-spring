package net.officefloor.hq.app.patron;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The patrons people on a sheet have named. */
@Service
public class PatronService {

    private final PatronRepository patrons;
    private final PersonRepository people;
    private final SheetService sheets;
    private final Audit audit;

    public PatronService(PatronRepository patrons, PersonRepository people, SheetService sheets, Audit audit) {
        this.sheets = sheets;
        this.patrons = patrons;
        this.people = people;
        this.audit = audit;
    }

    /** The patrons named on the sheet in the order they were named. */
    @Transactional(readOnly = true)
    public List<Patron> list(String sheetId) {
        return patrons.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Name another person on the sheet as a person's patron; refused for the person themselves and
     *  for a patron they have already named. */
    @Transactional
    public Patron add(String sheetId, String user, long personId, long patronId) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        Person patron = people.findByIdAndSheetId(patronId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such patron on this sheet"));
        if (person.getId().equals(patron.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A person cannot be their own patron");
        }
        if (patrons.existsByPersonIdAndPatronId(person.getId(), patron.getId())) {
            throw alreadyNamed();
        }
        Patron saved;
        try {
            saved = patrons.saveAndFlush(new Patron(sheetId, person.getId(), patron.getId(), sheets.today(sheetId)));
        } catch (DataIntegrityViolationException e) {
            throw alreadyNamed();
        }
        audit.recordOnCommit("PATRON_ADDED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " patron=" + patron.getName());
        return saved;
    }

    private static ResponseStatusException alreadyNamed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That patron is already named");
    }
}
