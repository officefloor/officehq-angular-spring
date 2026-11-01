package net.officefloor.hq.app.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import net.officefloor.hq.app.Audit;
import org.springframework.stereotype.Service;

/**
 * Reads back the audit file for a sheet: it is not a second record, only a view of what the file
 * holds.
 */
@Service
public class AuditViewService {

    private final Audit audit;

    public AuditViewService(Audit audit) {
        this.audit = audit;
    }

    /**
     * The sheet's audit lines exactly as the file holds them, newest first. With a name (blank: none)
     * only those carrying name=<the name> or person=<the name>, the name compared without surrounding
     * spaces and ignoring case.
     */
    public List<String> lines(String sheetId, String name) {
        String sheetField = "sheet=" + sheetId;
        String wanted = name == null ? "" : name.strip();
        Pattern about = wanted.isEmpty() ? null
                : Pattern.compile(" (?:name|person)=" + Pattern.quote(wanted) + "(?= [^ =]+=|$)",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        List<String> out = new ArrayList<>();
        for (String line : audit.lines()) {
            String[] fields = line.split(" ", 3);
            if (fields.length < 2 || !fields[1].equals(sheetField)) continue;
            if (about != null && !about.matcher(line).find()) continue;
            out.add(line);
        }
        Collections.reverse(out);
        return out;
    }
}
