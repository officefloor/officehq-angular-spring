package net.officefloor.hq.app.sheet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import net.officefloor.hq.app.history.AsOfGuard;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** While a user's screen previews a sheet as of a date, every request it makes carries that date in
 *  {@link #HEADER}: each read answers over the sheet's current records as if the sheet's today were
 *  that date, while every changing request ignores it and uses the sheet's real today. Nothing is
 *  stored for a preview, so nothing a change of today does happens. A screen showing the sheet as
 *  of a date ({@link AsOfGuard}) takes no preview. */
@Configuration
public class PreviewDate implements HandlerInterceptor, WebMvcConfigurer {

    /** The header carrying the date (yyyy-mm-dd) the screen previews the sheet as of. */
    public static final String HEADER = "X-Preview-Date";

    private static final Set<String> READING = Set.of("GET", "HEAD");

    private static final ThreadLocal<LocalDate> PREVIEW = new ThreadLocal<>();

    /** The date the read being answered previews the sheet as of, else the date given. */
    public static LocalDate or(LocalDate today) {
        LocalDate preview = PREVIEW.get();
        return preview != null ? preview : today;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/sheets/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        PREVIEW.remove();
        String preview = request.getHeader(HEADER);
        String asOf = request.getHeader(AsOfGuard.HEADER);
        if (preview == null || preview.isBlank() || (asOf != null && !asOf.isBlank()) || !READING.contains(request.getMethod())) {
            return true;
        }
        try {
            PREVIEW.set(LocalDate.parse(preview.trim()));
        } catch (DateTimeParseException e) {
            // not a date: the read answers as of the sheet's real today
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        PREVIEW.remove();
    }
}
