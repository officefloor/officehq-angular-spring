package net.officefloor.hq.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit viewer: the sheet's audit lines as the file holds them, newest first. Filtered by the name
 * of a person, the matching lines of the sheet's descendants follow (each sheet's newest first, sheets by
 * id); and, for a person transferred here, the lines about them on the sheet they came from, newest first.
 */
@RestController
@RequestMapping("/api/sheets/{sheetId}/audit")
@Validated
public class AuditController {

    private final Audit audit;
    private final PersonRepository people;
    private final SheetRepository sheets;

    public AuditController(Audit audit, PersonRepository people, SheetRepository sheets) {
        this.audit = audit;
        this.people = people;
        this.sheets = sheets;
    }

    /**
     * The sheet's audit lines, newest first; with a name, only the lines about that person, then, when
     * descendant sheets' lines about that name (sheets by id), then, when they came from another sheet,
     * that sheet's lines about them by their name or an alias.
     */
    @GetMapping
    public List<String> lines(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestParam(required = false) @Size(max = 200) String name) {
        List<String> lines = audit.linesOf(sheetId, name);
        if (name == null || name.isBlank()) {
            return lines;
        }
        List<String> shown = new ArrayList<>(lines);
        sheets.descendants(sheetId).stream().sorted()
                .forEach(descendant -> shown.addAll(audit.linesOf(descendant, name)));
        Optional<Person> person = people.findBySheetIdAndName(sheetId, name);
        if (person.isEmpty() || person.get().getOriginSheetId() == null) {
            return shown;
        }
        Set<String> keys = Stream.concat(Stream.of(person.get().getName()), person.get().getAliases().stream())
                .map(Person::keyOf).collect(Collectors.toSet());
        shown.addAll(audit.linesAbout(person.get().getOriginSheetId(), keys));
        return shown;
    }
}
