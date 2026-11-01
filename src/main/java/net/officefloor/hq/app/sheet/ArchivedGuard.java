package net.officefloor.hq.app.sheet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import net.officefloor.hq.app.setting.OpenToViewer;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Refuses every change asked of an archived sheet, before anything is written: any request to a
 * sheet other than a read, unless its handler is {@link OpenToViewer} (loading the sheet, or a bulk
 * change that itself refuses each selected item on an archived sheet).
 */
@Configuration
public class ArchivedGuard implements WebMvcConfigurer, HandlerInterceptor {

    private final SheetRepository sheets;

    public ArchivedGuard(SheetRepository sheets) {
        this.sheets = sheets;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/sheets/*/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod())) return true;
        if (!(handler instanceof HandlerMethod method) || method.hasMethodAnnotation(OpenToViewer.class)) return true;
        String sheetId = sheetId(request);
        if (sheetId == null) return true;
        if (sheets.findById(sheetId).map(Sheet::isArchived).orElse(false)) throw new SheetArchivedException();
        return true;
    }

    /** The sheet the request is for: its sheetId path variable, or the id of a request to the sheet itself. */
    private static String sheetId(HttpServletRequest request) {
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars == null) return null;
        return vars.getOrDefault("sheetId", vars.get("id"));
    }
}
