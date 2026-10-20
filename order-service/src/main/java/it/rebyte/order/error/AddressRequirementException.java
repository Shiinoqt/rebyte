package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/** V.Order.shippingAddressIffHardware and the EU-allowlist/postal-code checks (D-10). */
public class AddressRequirementException extends DomainException {

    public AddressRequirementException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-shipping-address", message);
    }
}
