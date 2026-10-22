package net.officefloor.hq.app.sheet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Refuse every change anyone asks for on an archived sheet: any request other than a read, or the
 * loading of the sheet, is turned away before it reaches its handler, so it writes nothing and audits
 * nothing. A bulk move is the exception: it reaches its handler, which refuses each claim selected.
 */
@Configuration
public class ArchiveGuard implements WebMvcConfigurer, HandlerInterceptor {

    private final SheetService sheets;

    public ArchiveGuard(SheetService sheets) {
        this.sheets = sheets;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // A bulk move answers how many claims it refused, so it is let through and refuses each.
        registry.addInterceptor(this).addPathPatterns("/api/sheets/*/**").excludePathPatterns("/api/sheets/*/load",
                "/api/sheets/*/claims/open", "/api/sheets/*/claims/settle");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod())) {
            return true;
        }
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(variables instanceof Map<?, ?> vars)) {
            return true;
        }
        Object sheetId = vars.containsKey("sheetId") ? vars.get("sheetId") : vars.get("id");
        if (sheetId instanceof String id && sheets.archived(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The sheet is archived: nothing on it may change.");
        }
        return true;
    }
}
