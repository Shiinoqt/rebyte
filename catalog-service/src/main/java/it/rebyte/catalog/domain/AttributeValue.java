package it.rebyte.catalog.domain;

import java.math.BigDecimal;

/**
 * A validated attribute value stored on {@link Listing#getAttributes()},
 * keyed there by the {@link AttributeDefinition#getId()} it was validated
 * against (D-02). Carries the {@link AttributeType} it was validated as
 * ALONGSIDE the value — exactly one of the four typed fields below is
 * non-null, matching {@link #getType()} — so a reader ({@code
 * ListingDocumentAssembler}, a future edit path) never has to re-derive the
 * type from the value's Java shape, which is ambiguous once round-tripped
 * through MongoDB/JSON.
 *
 * <p>Only {@link it.rebyte.catalog.service.AttributeValueValidator}
 * constructs instances of this class — every value stored here has already
 * passed the effective-schema check ATTR-03 requires.
 */
public class AttributeValue {

    private final AttributeType type;
    private final String stringValue;
    private final Long integerValue;
    private final BigDecimal decimalValue;
    private final Boolean booleanValue;

    public AttributeValue(AttributeType type, String stringValue, Long integerValue,
            BigDecimal decimalValue, Boolean booleanValue) {
        this.type = type;
        this.stringValue = stringValue;
        this.integerValue = integerValue;
        this.decimalValue = decimalValue;
        this.booleanValue = booleanValue;
    }

    public static AttributeValue ofEnum(String value) {
        return new AttributeValue(AttributeType.ENUM, value, null, null, null);
    }

    public static AttributeValue ofInteger(long value) {
        return new AttributeValue(AttributeType.INTEGER, null, value, null, null);
    }

    public static AttributeValue ofDecimal(BigDecimal value) {
        return new AttributeValue(AttributeType.DECIMAL, null, null, value, null);
    }

    public static AttributeValue ofBoolean(boolean value) {
        return new AttributeValue(AttributeType.BOOLEAN, null, null, null, value);
    }

    public AttributeType getType() {
        return type;
    }

    public String getStringValue() {
        return stringValue;
    }

    public Long getIntegerValue() {
        return integerValue;
    }

    public BigDecimal getDecimalValue() {
        return decimalValue;
    }

    public Boolean getBooleanValue() {
        return booleanValue;
    }

    /** The one non-null value this instance carries, typed to {@link #getType()}. */
    public Object anyValue() {
        return switch (type) {
            case ENUM -> stringValue;
            case INTEGER -> integerValue;
            case DECIMAL -> decimalValue;
            case BOOLEAN -> booleanValue;
        };
    }
}
