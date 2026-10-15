package net.officefloor.hq.app.people;

import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SheetController {

    /** A sheet: its id (null for the unnamed sheet) and its anchor date (today when none is set). */
    public record SheetView(String id, LocalDate anchor) {
    }

    private final PersonService service;

    public SheetController(PersonService service) {
        this.service = service;
    }

    /** The sheet named by the request, like the people API; blank or absent means the unnamed sheet. */
    @GetMapping("/api/sheet")
    public SheetView current(@RequestParam(required = false) String sheet) {
        if (sheet == null || sheet.isBlank()) {
            return new SheetView(null, service.anchor(null));
        }
        return get(sheet);
    }

    /** The named sheet, or 404 when no sheet has that id. */
    @GetMapping("/api/sheets/{id}")
    public SheetView get(@PathVariable String id) {
        String sheetId = id.trim();
        if (!service.sheetExists(sheetId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no sheet " + sheetId);
        }
        return new SheetView(sheetId, service.anchor(sheetId));
    }

    /** Opens the named sheet, starting a new empty one when no sheet has that id yet. */
    @PutMapping("/api/sheets/{id}")
    public SheetView open(@PathVariable String id) {
        String sheetId = id.trim();
        if (sheetId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a sheet id is required");
        }
        service.openSheet(sheetId);
        return new SheetView(sheetId, service.anchor(sheetId));
    }
}
