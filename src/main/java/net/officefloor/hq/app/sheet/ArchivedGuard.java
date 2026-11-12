package net.officefloor.hq.app.sheet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * An archived sheet stays visible and changes nothing: every change to it is refused before it reaches its
 * handler. Loading the sheet is not a change; archiving refuses an archived sheet itself, and a bulk action
 * goes through with nothing done (each claim refused), so those are left to their handlers.
 */
@Configuration
public class ArchivedGuard implements WebMvcConfigurer, HandlerInterceptor {

    private final SheetService sheets;

    public ArchivedGuard(SheetService sheets) {
        this.sheets = sheets;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this)
                .addPathPatterns("/api/sheets/*/**")
                .excludePathPatterns("/api/sheets/*/load", "/api/sheets/*/archive", "/api/sheets/*/claims/bulk-*");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())
                || "OPTIONS".equals(request.getMethod())) {
            return true;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) request
                .getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String sheetId = vars == null ? null : vars.getOrDefault("sheetId", vars.get("id"));
        if (sheetId != null) {
            sheets.requireNotArchived(sheetId.trim());
        }
        return true;
    }
}
