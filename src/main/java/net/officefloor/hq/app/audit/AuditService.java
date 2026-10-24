package net.officefloor.hq.app.audit;

import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The audit lines a sheet's viewer shows, read from the audit file as it holds them. */
@Service
public class AuditService {

    private final Audit audit;
    private final PersonRepository people;

    public AuditService(Audit audit, PersonRepository people) {
        this.audit = audit;
        this.people = people;
    }

    /**
     * The sheet's lines, newest first, or (with a name) only those about that person; when the person
     * so named on the sheet was transferred to it, followed by the lines about them from the sheet they
     * came from, newest first, as that sheet's lines hold them.
     */
    @Transactional(readOnly = true)
    public List<String> lines(String sheetId, String name) {
        List<String> lines = new ArrayList<>(audit.lines(sheetId, name));
        if (name != null && !name.isBlank()) {
            people.findBySheetIdAndNameKey(sheetId, Person.keyOf(name))
                    .map(Person::getOriginSheetId)
                    .ifPresent(origin -> lines.addAll(audit.lines(origin, name)));
        }
        return lines;
    }
}
