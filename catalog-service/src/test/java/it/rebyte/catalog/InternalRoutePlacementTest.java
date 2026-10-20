package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import java.lang.reflect.AnnotatedElement;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class InternalRoutePlacementTest {
    @Test
    void everyInternalMappingIsInTheInternalControllerPackage() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var candidates = scanner.findCandidateComponents("it.rebyte.catalog.controller");
        assertThat(candidates).isNotEmpty();
        boolean foundInternal = false;
        for (var candidate : candidates) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            boolean mapsInternal = internal(controller);
            for (var method : controller.getDeclaredMethods()) mapsInternal |= internal(method);
            if (mapsInternal) {
                foundInternal = true;
                assertThat(controller.getPackageName()).isEqualTo("it.rebyte.catalog.controller.internal");
            }
        }
        assertThat(foundInternal).isTrue();
    }

    private boolean internal(AnnotatedElement element) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(element, RequestMapping.class);
        if (mapping == null) return false;
        return java.util.stream.Stream.concat(java.util.Arrays.stream(mapping.value()), java.util.Arrays.stream(mapping.path()))
                .anyMatch(path -> path.startsWith("/internal"));
    }
}
