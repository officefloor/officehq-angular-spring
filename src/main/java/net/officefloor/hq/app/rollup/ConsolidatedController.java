package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets")
@Validated
public class ConsolidatedController {

    private final ConsolidatedService service;

    public ConsolidatedController(ConsolidatedService service) {
        this.service = service;
    }

    /** The figures of the sheet's descendants added up; the screen adds its own to consolidate them. */
    @GetMapping("/{id}/descendants")
    public ConsolidatedService.Descendants descendants(@PathVariable @NotBlank @Size(max = 100) String id) {
        return service.descendantsOf(id.trim());
    }
}
