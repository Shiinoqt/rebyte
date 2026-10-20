package it.rebyte.commons.media;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

/**
 * Hand-signed AWS Signature Version 4 for S3 browser-based POST uploads
 * (USER-03, D-34 AMENDED).
 *
 * <p>The AWS SDK for Java v2 has no presigned-POST support at all — confirmed
 * against two open, unmerged upstream issues (aws/aws-sdk-java-v2#1493, open
 * since 2019, and #6577/#6885) — so the POST policy is signed by hand here.
 * This is one publicly documented, deterministic HMAC-SHA256 construction
 * with a canonical reference algorithm and zero design decisions of its own:
 * only the JDK's own {@link Mac} primitive is used, no hand-written hash or
 * cipher implementation, and credentials are resolved exclusively through
 * the SDK's standard {@link AwsCredentialsProvider} chain — only the signing
 * arithmetic is hand-rolled here, never credential handling.
 *
 * <p><b>The string to sign is the base64-encoded policy itself — nothing
 * more.</b> Unlike header-based SigV4 requests, there is no canonical-request
 * hash wrapping it. This is the one sub-claim in 01-RESEARCH.md §R1 rated
 * MEDIUM confidence, and it is proven byte-for-byte in
 * {@code S3PostPolicySignerTest} against AWS's own published worked example
 * (fetched from the official S3 API Reference via the Wayback Machine, since
 * the live page now redirects to a JS-rendered shell — see the SUMMARY for
 * the exact archived URL and timestamp) before anything in {@code AvatarService} is written against it.
 *
 * <p>Structured as four separable, individually testable steps so a test
 * vector can pin each one: {@link #encodePolicyToBase64}, {@link
 * #deriveSigningKey}, {@link #sign}, and the composing {@link
 * #signPolicy(String, String, String)}.
 */
@Component
public class S3PostPolicySigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String TERMINATION_STRING = "aws4_request";
    private static final String SERVICE = "s3";

    private final AwsCredentialsProvider credentialsProvider;

    public S3PostPolicySigner(AwsCredentialsProvider credentialsProvider) {
        this.credentialsProvider = credentialsProvider;
    }

    /**
     * Resolves the caller's credentials through the SDK's standard chain.
     * Exposed so {@code AvatarService} can build the {@code x-amz-credential}
     * (and, for temporary credentials, {@code x-amz-security-token}) POLICY
     * CONDITIONS with the exact access key and session token before the
     * policy is signed — the policy's {@code x-amz-credential} condition is
     * an exact match, so the form field the client sends must be byte-
     * identical to what went into the signed policy. This is still the only
     * place credential resolution happens; no literal key exists anywhere
     * outside this class.
     */
    public AwsCredentials resolveCredentials() {
        return credentialsProvider.resolveCredentials();
    }

    /**
     * The fields a browser-based POST upload needs from the signer, beyond
     * the caller-assembled bucket/key/target-URL. {@code sessionToken} is
     * {@code null} for long-lived (non-STS) credentials; when non-null it
     * must be sent both as a policy condition and as a form field, or the
     * upload is refused.
     */
    public record SignedPolicy(String policyBase64, String signature, String accessKeyId, String sessionToken) {}

    /**
     * Step 1 (of the signing arithmetic; policy JSON construction itself
     * lives in the caller): base64-encodes the UTF-8 policy JSON. This
     * base64 string IS the string to sign — there is no further wrapping.
     */
    public String encodePolicyToBase64(String policyJson) {
        return Base64.getEncoder().encodeToString(policyJson.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Step 2: derives the signing key via the standard four-step SigV4
     * chain — key material from the secret, then date, then region, then
     * the fixed {@code "s3"} service, terminating in the fixed
     * request-scope literal {@code "aws4_request"}.
     */
    public byte[] deriveSigningKey(String secretAccessKey, String dateStamp, String region) {
        byte[] kDate = hmacSha256(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, SERVICE);
        return hmacSha256(kService, TERMINATION_STRING);
    }

    /** Step 3: HMAC-SHA256 of the string to sign under the derived signing key, hex-encoded. */
    public String sign(byte[] signingKey, String stringToSign) {
        return HexFormat.of().formatHex(hmacSha256(signingKey, stringToSign));
    }

    /**
     * Composes steps 1-3 against credentials resolved from the SDK's
     * standard provider chain (never a literal key here).
     */
    public SignedPolicy signPolicy(String policyJson, String dateStamp, String region) {
        AwsCredentials credentials = credentialsProvider.resolveCredentials();
        String policyBase64 = encodePolicyToBase64(policyJson);
        byte[] signingKey = deriveSigningKey(credentials.secretAccessKey(), dateStamp, region);
        String signature = sign(signingKey, policyBase64);
        String sessionToken = credentials instanceof AwsSessionCredentials sessionCredentials
                ? sessionCredentials.sessionToken()
                : null;
        return new SignedPolicy(policyBase64, signature, credentials.accessKeyId(), sessionToken);
    }

    private byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 signing failed", e);
        }
    }
}
