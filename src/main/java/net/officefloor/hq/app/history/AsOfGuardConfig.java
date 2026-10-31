package net.officefloor.hq.app.history;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts the {@link AsOfGuard} in front of a sheet's API, except loading it. */
@Configuration
public class AsOfGuardConfig implements WebMvcConfigurer {

    private final AsOfGuard guard;

    public AsOfGuardConfig(AsOfGuard guard) {
        this.guard = guard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard)
                .addPathPatterns("/api/sheets/**")
                .excludePathPatterns("/api/sheets/*/load");
    }
}
