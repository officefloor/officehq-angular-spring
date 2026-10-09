package net.officefloor.hq.app.currency;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/currencies")
public class CurrencyController {

    private final CurrencyService service;

    public CurrencyController(CurrencyService service) {
        this.service = service;
    }

    @GetMapping
    public List<CurrencyResponse> list() {
        return service.list();
    }

    @PutMapping("/{code}/rounding")
    public CurrencyResponse changeRounding(@PathVariable String code, @Valid @RequestBody CurrencyRoundingRequest request) {
        return service.changeRounding(code, request);
    }
}
