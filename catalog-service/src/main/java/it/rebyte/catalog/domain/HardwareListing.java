package it.rebyte.catalog.domain;

import java.math.BigDecimal;

/**
 * Parte hardware embedded su {@link Listing} — {@code condition},
 * {@code shippingLocation} e {@code shippingCost} restano campi tipati fissi
 * esattamente come ch. 12 li dichiara (D-04, 02-CONTEXT.md), mai attribute
 * definition. {@code shippingCost} e un {@link BigDecimal}, persistito come
 * BSON {@code Decimal128} dalla stessa {@link it.rebyte.catalog.config.MongoConfig}
 * che copre {@link Listing#getPrice()}.
 */
public class HardwareListing {

    private final Condition condition;
    private final String shippingLocation;
    private final BigDecimal shippingCost;

    public HardwareListing(Condition condition, String shippingLocation, BigDecimal shippingCost) {
        this.condition = condition;
        this.shippingLocation = shippingLocation;
        this.shippingCost = shippingCost;
    }

    public Condition getCondition() {
        return condition;
    }

    public String getShippingLocation() {
        return shippingLocation;
    }

    public BigDecimal getShippingCost() {
        return shippingCost;
    }
}
