package it.rebyte.payment.provider;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Record DTOs for exactly the PayPal REST fields {@link PayPalClient} reads
 * or writes (D-23: no PayPal SDK, no full-schema modeling). Shapes verified
 * live against the real sandbox in Plan 03-01 ({@code 03-SANDBOX-SPIKE.md},
 * {@code scripts/paypal/sandbox-spike.py}) — the same
 * {@code payment_source.paypal.experience_context} request shape and
 * {@code purchase_units[].payments.captures[]} response shape that probe
 * proved against the real PayPal sandbox.
 */
public final class PayPalDtos {

    private PayPalDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenResponse(@JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresInSeconds) {
    }

    public record Money(@JsonProperty("currency_code") String currencyCode, String value) {
    }

    public record AmountBreakdown(@JsonProperty("item_total") Money itemTotal, Money shipping) {
    }

    public record Amount(@JsonProperty("currency_code") String currencyCode, String value, AmountBreakdown breakdown) {
    }

    public record Item(String name, String quantity, @JsonProperty("unit_amount") Money unitAmount) {
    }

    public record ExperienceContext(@JsonProperty("return_url") String returnUrl,
            @JsonProperty("cancel_url") String cancelUrl, @JsonProperty("user_action") String userAction,
            @JsonProperty("landing_page") String landingPage) {
    }

    public record PaypalPaymentSource(@JsonProperty("experience_context") ExperienceContext experienceContext) {
    }

    public record PaymentSource(PaypalPaymentSource paypal) {
    }

    public record PurchaseUnit(@JsonProperty("custom_id") String customId, Amount amount, List<Item> items) {
    }

    public record OrderRequest(String intent, @JsonProperty("purchase_units") List<PurchaseUnit> purchaseUnits,
            @JsonProperty("payment_source") PaymentSource paymentSource) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Link(String href, String rel) {
    }

    /**
     * Orders v2 create/get response. {@link #approvalUrl()} picks the {@code
     * payer-action} link (guest/card flow) or, failing that, {@code
     * approve} (research: "choose the link with rel payer-action or approve
     * from the response rather than assuming one").
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderResponse(String id, String status, List<Link> links) {

        public String approvalUrl() {
            if (links == null) {
                return null;
            }
            return links.stream()
                    .filter(link -> "payer-action".equals(link.rel()))
                    .map(Link::href)
                    .findFirst()
                    .or(() -> links.stream().filter(link -> "approve".equals(link.rel())).map(Link::href).findFirst())
                    .orElse(null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CaptureResult(String id, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PurchaseUnitPayments(List<CaptureResult> captures) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PurchaseUnitCapture(PurchaseUnitPayments payments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CaptureResponse(String id, String status,
            @JsonProperty("purchase_units") List<PurchaseUnitCapture> purchaseUnits) {

        public CaptureResult firstCapture() {
            if (purchaseUnits == null) {
                return null;
            }
            return purchaseUnits.stream()
                    .map(PurchaseUnitCapture::payments)
                    .filter(p -> p != null && p.captures() != null && !p.captures().isEmpty())
                    .map(p -> p.captures().get(0))
                    .findFirst()
                    .orElse(null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RefundResponse(String id, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProviderError(String name, String message, List<ErrorDetail> details) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorDetail(String issue, String description) {
    }
}
