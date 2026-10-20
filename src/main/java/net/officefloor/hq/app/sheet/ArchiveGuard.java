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

/** Refuse every change asked of an archived sheet: any request on it other than a read, before it reaches its
 * handler, so it writes nothing and records nothing. Loading the sheet is a read. Opening or settling claims
 * together goes through, so that each claim is refused on its own and counted as refused. */
@Configuration
public class ArchiveGuard implements HandlerInterceptor, WebMvcConfigurer {

    private final ArchiveService archives;

    public ArchiveGuard(ArchiveService archives) {
        this.archives = archives;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/sheets/{sheet}/**").excludePathPatterns("/api/sheets/load",
                "/api/sheets/{sheet}/claims/open", "/api/sheets/{sheet}/claims/settle");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod())
                || HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) request
                .getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String sheetId = vars == null ? null : vars.getOrDefault("sheetId", vars.get("id"));
        if (sheetId != null) {
            archives.requireNotArchived(sheetId);
        }
        return true;
    }
}
