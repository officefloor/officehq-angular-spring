package net.officefloor.hq.app.sheet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Refuses every change asked of an archived sheet before it reaches its controller, so it writes nothing.
 * Reads go through, as does loading the sheet; a bulk change goes through to {@code ClaimBulkService},
 * which refuses each claim asked of an archived sheet. */
@Configuration
public class SheetArchivedGuard implements WebMvcConfigurer, HandlerInterceptor {

    private final SheetRepository sheets;

    public SheetArchivedGuard(SheetRepository sheets) {
        this.sheets = sheets;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this)
                .addPathPatterns("/api/sheets/*/**")
                .excludePathPatterns("/api/sheets/*/load", "/api/sheets/*/locale", "/api/sheets/*/columns", "/api/sheets/*/claims/bulk-*",
                        "/api/sheets/*/people/bulk-grade", "/api/sheets/*/people/bulk-retire", "/api/sheets/*/people/bulk-hold");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod())) return true;
        String sheetId = sheetId(request);
        if (sheetId == null) return true;
        if (sheets.findById(sheetId.trim()).map(Sheet::isArchived).orElse(false)) throw new SheetArchivedException();
        return true;
    }

    @SuppressWarnings("unchecked")
    private static String sheetId(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(vars instanceof Map<?, ?> map)) return null;
        Object id = map.containsKey("sheetId") ? map.get("sheetId") : map.get("id");
        return id == null ? null : id.toString();
    }
}
