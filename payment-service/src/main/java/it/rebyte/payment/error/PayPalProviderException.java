package it.rebyte.payment.error;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import it.rebyte.commons.error.DomainException;

/**
 * Wraps a PayPal provider 4xx/5xx response or a connect/read timeout as a
 * single, non-secret-leaking domain error (03-RESEARCH.md "Concrete PayPal
 * fields"; T-03-12). Never carries the raw response body — only the
 * provider's classification ({@code name}, {@code details[].issue}), per
 * {@code scripts/paypal/sandbox-spike.py}'s {@code extract_provider_error}
 * note that a body dump can carry PII.
 *
 * <p>{@link #getProviderStatus()}/{@link #getProviderErrorName()}/{@link
 * #getProviderIssues()} are exposed (03-10-PLAN.md Task 1) so {@link
 * it.rebyte.payment.service.PaymentReconciliationService} can classify a
 * qualified 404 {@code RESOURCE_NOT_FOUND}/{@code INVALID_RESOURCE_ID} GET
 * response — the exact shape {@code 03-SANDBOX-SPIKE.md}'s GET-AFTER-EXPIRY
 * row observed live — as terminal no-charge proof, distinct from a timeout,
 * an unqualified error, or an ambiguous 422. All three are {@code null} for
 * the timeout/unreachable constructor.
 */
public class PayPalProviderException extends DomainException {

    private final HttpStatusCode providerStatus;
    private final String providerErrorName;
    private final List<String> providerIssues;

    public PayPalProviderException(HttpStatusCode providerStatus, String providerErrorName) {
        this(providerStatus, providerErrorName, List.of());
    }

    public PayPalProviderException(HttpStatusCode providerStatus, String providerErrorName,
            List<String> providerIssues) {
        super(HttpStatus.BAD_GATEWAY, "paypal-provider-error",
                "PayPal provider error" + (providerErrorName != null ? " (" + providerErrorName + ")" : "")
                        + ", status " + providerStatus.value());
        this.providerStatus = providerStatus;
        this.providerErrorName = providerErrorName;
        this.providerIssues = providerIssues == null ? List.of() : List.copyOf(providerIssues);
    }

    public PayPalProviderException(String message, Throwable cause) {
        super(HttpStatus.GATEWAY_TIMEOUT, "paypal-provider-timeout", message);
        initCause(cause);
        this.providerStatus = null;
        this.providerErrorName = null;
        this.providerIssues = List.of();
    }

    /** The PayPal HTTP status actually returned, or {@code null} for a timeout/unreachable failure. */
    public HttpStatusCode getProviderStatus() {
        return providerStatus;
    }

    public String getProviderErrorName() {
        return providerErrorName;
    }

    /** {@code details[].issue} values from the PayPal error body, or empty when absent/unparseable/a timeout. */
    public List<String> getProviderIssues() {
        return providerIssues;
    }
}
