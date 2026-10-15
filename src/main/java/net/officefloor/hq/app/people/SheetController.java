package net.officefloor.hq.app.people;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sheets")
public class SheetController {

    public record SheetView(String id) {
    }

    private final PersonService service;

    public SheetController(PersonService service) {
        this.service = service;
    }

    /** The named sheet, or 404 when no sheet has that id. */
    @GetMapping("/{id}")
    public SheetView get(@PathVariable String id) {
        String sheetId = id.trim();
        if (!service.sheetExists(sheetId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no sheet " + sheetId);
        }
        return new SheetView(sheetId);
    }
}
