package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import it.rebyte.messaging.dto.StartConversationRequest;
import jakarta.validation.Validation;

class StartConversationRequestTest {

    @ParameterizedTest
    @ValueSource(strings = {"../users/alice", "listing/child", "%2e%2e%2fusers", "listing", "", " ",
            "11111111-1111-1111-1111-11111111111z", "11111111-1111-1111-1111-111111111111/"})
    void invalidListingIdsFailBeanValidation(String id) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new StartConversationRequest(id, "Hello", null)))
                    .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("listingId"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"9d391d4b-04e9-4e1f-bc96-bd4a800942b8", "9D391D4B-04E9-4E1F-BC96-BD4A800942B8"})
    void canonicalUuidListingIdsPassBeanValidation(String id) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new StartConversationRequest(id, "Hello", null))).isEmpty();
        }
    }
}
