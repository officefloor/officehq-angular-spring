package net.officefloor.hq.app.audit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The audit viewer: the audit lines of a sheet exactly as the audit file holds them, newest first. Given a
 * name, only the lines about that person: those carrying {@code name=} or {@code person=} that name, compared
 * as names are (case and surrounding spaces ignored); the sheet's descendants' lines about them follow (each
 * sheet's newest first, the sheets in id order), then, when that person was transferred here, the sheet they
 * came from's lines about them, newest first. A claim's timeline is the sheet's lines about that claim, newest
 * first: those carrying its number, or for a claim with no number those carrying no number that name its
 * person, amount and date together. The sheet's activity is, for every user and every event in its lines, how
 * many lines that user's event has, sorted by user then event. It reads the file; it records nothing.
 */
@RestController
@RequestMapping("/api/sheets/{sheetId}/audit")
@Validated
public class AuditController {

    /** A field of a line: its key, and its value running up to the next field (a value may hold spaces). */
    private static final Pattern FIELD = Pattern.compile("(?:^|\\s)([A-Za-z][\\w-]*)=(.*?)(?=\\s[A-Za-z][\\w-]*=|$)");

    private final Audit audit;
    private final PersonRepository people;
    private final SheetService sheets;
    private final ClaimRepository claims;

    public AuditController(Audit audit, PersonRepository people, SheetService sheets, ClaimRepository claims) {
        this.audit = audit;
        this.claims = claims;
        this.people = people;
        this.sheets = sheets;
    }

    @GetMapping
    public List<String> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestParam(required = false) String name) {
        String sheet = sheetId.trim();
        String who = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        List<String> all = audit.lines();
        List<String> lines = about(all, sheet, who);
        if (!who.isEmpty()) {
            sheets.descendants(sheet).stream().sorted()
                    .forEach(descendant -> lines.addAll(about(all, descendant, who)));
            people.findBySheetIdAndNameIgnoreCase(sheet, name.strip()).map(Person::getOrigin)
                    .ifPresent(origin -> lines.addAll(about(all, origin, who)));
        }
        return lines;
    }

    /** A claim's timeline: the sheet's lines about it, newest first. */
    @GetMapping("/claims/{claimId}")
    public List<String> timeline(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long claimId) {
        String sheet = sheetId.trim();
        Claim claim = claims.findByIdAndSheetId(claimId, sheet)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        String number = claim.getShownNumber();
        String person = people.findByIdAndSheetId(claim.getPersonId(), sheet).map(Person::getName).orElse("")
                .strip().toLowerCase(Locale.ROOT);
        String amount = claim.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString();
        String date = String.valueOf(claim.getDate());
        List<String> lines = new ArrayList<>(audit.lines().stream()
                .filter(l -> carries(l, "sheet", sheet, false))
                .filter(l -> number != null
                        ? carries(l, "number", number, false)
                        : !has(l, "number") && carries(l, "person", person, true)
                                && carries(l, "amount", amount, false) && carries(l, "date", date, false))
                .toList());
        Collections.reverse(lines);
        return lines;
    }

    /** The sheet's activity: one {@code <user> <EVENT> <count>} per user and event, sorted by user then event. */
    @GetMapping("/activity")
    public List<String> activity(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        String sheet = sheetId.trim();
        Map<String, Map<String, Long>> counts = new TreeMap<>();
        for (String line : audit.lines()) {
            if (!carries(line, "sheet", sheet, false)) {
                continue;
            }
            String user = value(line, "by");
            if (user == null) {
                continue;
            }
            String event = line.split(" ", 2)[0];
            counts.computeIfAbsent(user, u -> new TreeMap<>()).merge(event, 1L, Long::sum);
        }
        List<String> lines = new ArrayList<>();
        counts.forEach((user, events) -> events.forEach((event, n) -> lines.add(user + " " + event + " " + n)));
        return lines;
    }

    /** The line's first value for the key, or null when it has none. */
    private static String value(String line, String key) {
        Matcher m = FIELD.matcher(line);
        while (m.find()) {
            if (m.group(1).equals(key)) {
                return m.group(2);
            }
        }
        return null;
    }

    /** The sheet's lines, newest first; given a name (lower-cased), only those about that person. */
    private static List<String> about(List<String> all, String sheet, String who) {
        List<String> lines = new ArrayList<>(all.stream()
                .filter(l -> carries(l, "sheet", sheet, false))
                .filter(l -> who.isEmpty() || carries(l, "name", who, true) || carries(l, "person", who, true))
                .toList());
        Collections.reverse(lines);
        return lines;
    }

    private static boolean has(String line, String key) {
        Matcher m = FIELD.matcher(line);
        while (m.find()) {
            if (m.group(1).equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean carries(String line, String key, String value, boolean asName) {
        Matcher m = FIELD.matcher(line);
        while (m.find()) {
            if (!m.group(1).equals(key)) {
                continue;
            }
            String v = m.group(2);
            if (asName ? v.strip().toLowerCase(Locale.ROOT).equals(value) : v.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
