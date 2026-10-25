package net.officefloor.hq.app.sheet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** The preview date of a screen that shows its sheet as if its today were that date (one whose requests
 * carry the {@value #HEADER} header), over the sheet's current records. Only a read takes it: every figure
 * it works out uses that date as the sheet's today. A change ignores it and uses the sheet's real today, so
 * nothing a change of today does (snapshots, scores, automatic closes) happens. */
@Configuration
public class PreviewDate implements WebMvcConfigurer, HandlerInterceptor {

    /** The header a screen with a preview date sends that date in (YYYY-MM-DD). */
    public static final String HEADER = "X-Preview";

    /** The preview date of the read being served on this thread; null when there is none. */
    private static final ThreadLocal<LocalDate> PREVIEW = new ThreadLocal<>();

    /** The date a read's figures take as the sheet's today: the preview date when one is set, else {@code today}. */
    public static LocalDate shown(LocalDate today) {
        LocalDate preview = PREVIEW.get();
        return preview == null || today == null ? today : preview;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/sheets/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        PREVIEW.remove();
        if (!HttpMethod.GET.matches(request.getMethod()) && !HttpMethod.HEAD.matches(request.getMethod())) return true;
        String preview = request.getHeader(HEADER);
        if (preview == null || preview.isBlank()) return true;
        try {
            PREVIEW.set(LocalDate.parse(preview.trim()));
        } catch (DateTimeParseException e) {
            // Not a date: the read shows the sheet's today.
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        PREVIEW.remove();
    }
}
