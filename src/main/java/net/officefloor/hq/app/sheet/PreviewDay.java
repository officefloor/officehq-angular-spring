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

/** The preview date a user's screen sends (in {@value #HEADER}, yyyy-mm-dd) with its reads: while a
 *  read is answered, every sheet's today is taken to be that day, over the sheet's current records.
 *  A change never takes it, so every change still uses the sheet's real today. */
@Configuration
public class PreviewDay implements HandlerInterceptor, WebMvcConfigurer {

    /** The header a screen previewing a date sends that date in. */
    public static final String HEADER = "X-Preview";

    private static final ThreadLocal<LocalDate> DAY = new ThreadLocal<>();

    /** The preview date of the read being answered; null when there is none. */
    public static LocalDate current() {
        return DAY.get();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/sheets/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        DAY.remove();
        String day = request.getHeader(HEADER);
        if (day != null && !day.isBlank() && HttpMethod.GET.matches(request.getMethod())) {
            try {
                DAY.set(LocalDate.parse(day.trim()));
            } catch (DateTimeParseException e) {
                // a date that does not read previews nothing
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        DAY.remove();
    }
}
