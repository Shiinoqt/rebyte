package it.rebyte.catalog.dto;

import tools.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * One entry of {@code PublishHardwareRequest.attributes()} — an
 * attribute-definition id paired with its raw JSON value. Deliberately
 * carries no {@code type} field: the client never declares a type
 * alongside the value (T-02-03-01, the attribute type-confusion threat) —
 * {@link it.rebyte.catalog.service.AttributeValueValidator} decides what
 * shape {@code value} must have from the effective schema's OWN declared
 * type for {@code attributeId}, never from anything the client asserts.
 *
 * <p>{@code JsonNode} here is {@code tools.jackson.databind.JsonNode}
 * (Jackson 3, the package Spring Framework 7 / Boot 4.1's own HTTP message
 * converter deserializes {@code @RequestBody} with) — NOT the classic
 * {@code com.fasterxml.jackson.databind.JsonNode} this codebase's
 * hand-wired {@code ObjectMapper} beans (outbox payloads, the category-tree
 * cache) still use for their own manual (de)serialization. Both Jackson
 * major versions are present on the classpath; the request-body binding
 * path fails at runtime with an unconstructible-abstract-type error if this
 * import picks the wrong one — found via genuine RED evidence running
 * {@code PublishHardwareIT} against the real HTTP endpoint.
 */
public record AttributeValueInput(

        @NotBlank
        String attributeId,

        @NotNull
        JsonNode value) {
}
