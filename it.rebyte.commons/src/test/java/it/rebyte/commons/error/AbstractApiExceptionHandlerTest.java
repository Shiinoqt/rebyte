package it.rebyte.commons.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Proves the error contract itself (D-13, D-30, D-31) against a minimal
 * MockMvc-standalone controller — independent of any real service's domain
 * exceptions, which do not exist until plan 01-03 Task 2 in user-service.
 *
 * <p>{@code standaloneSetup} deliberately avoids {@code @WebMvcTest}: this
 * module has no {@code @SpringBootApplication} for that annotation's
 * context-loading machinery to find, and none is needed to prove the
 * advice's wiring in isolation.
 */
class AbstractApiExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new TestController())
            .setControllerAdvice(new TestApiExceptionHandler())
            .build();

    @Test
    void domainExceptionProducesProblemJsonWithItsOwnStatus() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").value("test-conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("conflicting thing"));
    }

    @Test
    void validationFailureProducesProblemJsonWithErrorsExtension() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.name").exists());
    }

    @Test
    void frameworkOriginatedExceptionStillProducesProblemBody() throws Exception {
        // Wrong content type for a @RequestBody-JSON endpoint: Spring MVC
        // itself raises HttpMediaTypeNotSupportedException before any
        // controller or validation code runs — proving the auto-configured
        // handler did not back off into nothing (D-30's Spring Boot 4.1.0
        // gotcha).
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @RestController
    static class TestController {

        @GetMapping("/test/conflict")
        void conflict() {
            throw new TestDomainException();
        }

        @PostMapping("/test/validate")
        void validate(@Valid @RequestBody TestBody body) {
        }
    }

    record TestBody(@NotBlank String name) {
    }

    static class TestDomainException extends DomainException {
        TestDomainException() {
            super(HttpStatus.CONFLICT, "test-conflict", "conflicting thing");
        }
    }

    @RestControllerAdvice
    static class TestApiExceptionHandler extends AbstractApiExceptionHandler {
    }
}
