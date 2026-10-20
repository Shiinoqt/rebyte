package it.rebyte.commons.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import it.rebyte.commons.media.S3PostPolicySigner;
import it.rebyte.commons.media.S3PostPolicySigner.SignedPolicy;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * Proves {@link S3PostPolicySigner} against AWS's own published worked
 * example BEFORE anything else in the plan depends on it (01-06-PLAN.md,
 * Task 1; D-34 AMENDED).
 *
 * <p><b>Source of the test vector.</b> AWS's S3 API Reference page
 * "Example: Browser-Based Upload using HTTP POST (Using AWS Signature
 * Version 4)" — originally at
 * {@code https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-post-example.html}.
 * That live URL now 302-redirects to a JS-rendered {@code Welcome.html}
 * shell (AWS restructured the S3 docs site after this page was written and
 * no longer serves the old path directly), so the content below was
 * retrieved from the Wayback Machine's archive of the same official AWS
 * page, snapshot timestamp {@code 20231211155811}:
 * {@code https://web.archive.org/web/20231211155811/https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-post-example.html}.
 * This is the vendor's own worked example, not a third-party summary or
 * reference implementation — the archive is only standing in for AWS's own
 * hosting, which moved the page.
 *
 * <p>The page states verbatim: "The topic uses the example policy and
 * fictitious credentials to show you the workflow and resulting signature
 * and policy hash. You can use this data as test suite to verify your
 * signature calculation code." The example credentials
 * ({@code AKIAIOSFODNN7EXAMPLE} / {@code wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY})
 * are AWS's own well-known placeholder credentials used across its SigV4
 * documentation, not a secret.
 *
 * <p>Before writing this test, the vector was independently reproduced in a
 * throwaway Python script implementing the identical four-step key
 * derivation plus a direct HMAC over the base64 policy — confirming the
 * published signature is internally consistent with the "no further
 * wrapping" construction before any Java code was trusted to reproduce it.
 *
 * <p>The published example does not separately publish the four
 * intermediate signing-key values ({@code kDate}/{@code kRegion}/
 * {@code kService}/{@code kSigning}) — only the final hex signature — so
 * only the final signature is pinned here; there is nothing published to
 * pin the intermediates against for this specific vector.
 */
class S3PostPolicySignerTest {

    // AWS's own published example credentials — not a secret (see class Javadoc).
    private static final String EXAMPLE_ACCESS_KEY_ID = "AKIAIOSFODNN7EXAMPLE";
    private static final String EXAMPLE_SECRET_ACCESS_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final String EXAMPLE_DATE_STAMP = "20151229";
    private static final String EXAMPLE_REGION = "us-east-1";

    // Decoded verbatim from the base64 "Policy" value published on the AWS page (see class
    // Javadoc), preserving the exact CRLF line terminators the page explicitly calls out as
    // required for the hash to match.
    private static final String EXAMPLE_POLICY_JSON = "{ \"expiration\": \"2015-12-30T12:00:00.000Z\",\r\n"
            + "  \"conditions\": [\r\n"
            + "    {\"bucket\": \"sigv4examplebucket\"},\r\n"
            + "    [\"starts-with\", \"$key\", \"user/user1/\"],\r\n"
            + "    {\"acl\": \"public-read\"},\r\n"
            + "    {\"success_action_redirect\": \"http://sigv4examplebucket.s3.amazonaws.com/successful_upload.html\"},\r\n"
            + "    [\"starts-with\", \"$Content-Type\", \"image/\"],\r\n"
            + "    {\"x-amz-meta-uuid\": \"14365123651274\"},\r\n"
            + "    {\"x-amz-server-side-encryption\": \"AES256\"},\r\n"
            + "    [\"starts-with\", \"$x-amz-meta-tag\", \"\"],\r\n"
            + "\r\n"
            + "    {\"x-amz-credential\": \"AKIAIOSFODNN7EXAMPLE/20151229/us-east-1/s3/aws4_request\"},\r\n"
            + "    {\"x-amz-algorithm\": \"AWS4-HMAC-SHA256\"},\r\n"
            + "    {\"x-amz-date\": \"20151229T000000Z\" }\r\n"
            + "  ]\r\n"
            + "}";

    // The base64 "Policy" value published verbatim on the AWS page — the StringToSign.
    private static final String EXAMPLE_POLICY_BASE64 =
            "eyAiZXhwaXJhdGlvbiI6ICIyMDE1LTEyLTMwVDEyOjAwOjAwLjAwMFoiLA0KICAiY29uZGl0aW9ucyI6IFsNCiAgICB7ImJ1Y2tldCI6"
                    + "ICJzaWd2NGV4YW1wbGVidWNrZXQifSwNCiAgICBbInN0YXJ0cy13aXRoIiwgIiRrZXkiLCAidXNlci91c2VyMS8iXSwNCiAgICB7"
                    + "ImFjbCI6ICJwdWJsaWMtcmVhZCJ9LA0KICAgIHsic3VjY2Vzc19hY3Rpb25fcmVkaXJlY3QiOiAiaHR0cDovL3NpZ3Y0ZXhhbXBs"
                    + "ZWJ1Y2tldC5zMy5hbWF6b25hd3MuY29tL3N1Y2Nlc3NmdWxfdXBsb2FkLmh0bWwifSwNCiAgICBbInN0YXJ0cy13aXRoIiwgIiRD"
                    + "b250ZW50LVR5cGUiLCAiaW1hZ2UvIl0sDQogICAgeyJ4LWFtei1tZXRhLXV1aWQiOiAiMTQzNjUxMjM2NTEyNzQifSwNCiAgICB7"
                    + "IngtYW16LXNlcnZlci1zaWRlLWVuY3J5cHRpb24iOiAiQUVTMjU2In0sDQogICAgWyJzdGFydHMtd2l0aCIsICIkeC1hbXotbWV0"
                    + "YS10YWciLCAiIl0sDQoNCiAgICB7IngtYW16LWNyZWRlbnRpYWwiOiAiQUtJQUlPU0ZPRE5ON0VYQU1QTEUvMjAxNTEyMjkvdXMt"
                    + "ZWFzdC0xL3MzL2F3czRfcmVxdWVzdCJ9LA0KICAgIHsieC1hbXotYWxnb3JpdGhtIjogIkFXUzQtSE1BQy1TSEEyNTYifSwNCiAg"
                    + "ICB7IngtYW16LWRhdGUiOiAiMjAxNTEyMjlUMDAwMDAwWiIgfQ0KICBdDQp9";

    // The signature value AWS's own page publishes for this exact policy + these exact
    // credentials + this exact date (see class Javadoc).
    private static final String EXPECTED_SIGNATURE =
            "8afdbf4008c03f22c2cd3cdb72e4afbb1f6a588f3255ac628749a66d7f09699e";

    private final S3PostPolicySigner signer = new S3PostPolicySigner(
            StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(EXAMPLE_ACCESS_KEY_ID, EXAMPLE_SECRET_ACCESS_KEY)));

    @Test
    void base64EncodingOfPublishedPolicyMatchesThePublishedPolicyValue() {
        // Pins step 1 in isolation: the encoder must reproduce AWS's own published base64
        // "Policy" value exactly, byte for byte — before it is ever used as a string to sign.
        assertThat(signer.encodePolicyToBase64(EXAMPLE_POLICY_JSON)).isEqualTo(EXAMPLE_POLICY_BASE64);
    }

    @Test
    void base64PolicyRoundTripsBackToTheExactPolicyJsonItWasGiven() {
        String policyBase64 = signer.encodePolicyToBase64(EXAMPLE_POLICY_JSON);
        String roundTripped = new String(Base64.getDecoder().decode(policyBase64), StandardCharsets.UTF_8);
        assertThat(roundTripped).isEqualTo(EXAMPLE_POLICY_JSON);
    }

    @Test
    void signatureOfPublishedPolicyMatchesThePublishedSignatureExactly() {
        // The gate this task exists for: reproduce AWS's own published signature byte-for-byte
        // from the same inputs (must_haves.truths #1, 01-06-PLAN.md).
        byte[] signingKey = signer.deriveSigningKey(EXAMPLE_SECRET_ACCESS_KEY, EXAMPLE_DATE_STAMP, EXAMPLE_REGION);
        String signature = signer.sign(signingKey, EXAMPLE_POLICY_BASE64);

        assertThat(signature).isEqualTo(EXPECTED_SIGNATURE);
    }

    @Test
    void endToEndSignPolicyAgainstResolvedCredentialsMatchesThePublishedSignature() {
        // Exercises the full composed path — credentials resolved via the SDK's
        // AwsCredentialsProvider, never a literal secret inside S3PostPolicySigner itself.
        SignedPolicy signed = signer.signPolicy(EXAMPLE_POLICY_JSON, EXAMPLE_DATE_STAMP, EXAMPLE_REGION);

        assertThat(signed.policyBase64()).isEqualTo(EXAMPLE_POLICY_BASE64);
        assertThat(signed.signature()).isEqualTo(EXPECTED_SIGNATURE);
        assertThat(signed.accessKeyId()).isEqualTo(EXAMPLE_ACCESS_KEY_ID);
        assertThat(signed.sessionToken()).isNull();
    }

    @Test
    void oneByteChangeToThePolicyProducesADifferentSignature() {
        // So this test cannot pass vacuously against a stubbed signer that ignores its input
        // (acceptance criteria, 01-06-PLAN.md Task 1).
        String tamperedPolicyJson = EXAMPLE_POLICY_JSON.replaceFirst("public-read", "public-reae");
        String tamperedPolicyBase64 = signer.encodePolicyToBase64(tamperedPolicyJson);

        byte[] signingKey = signer.deriveSigningKey(EXAMPLE_SECRET_ACCESS_KEY, EXAMPLE_DATE_STAMP, EXAMPLE_REGION);
        String tamperedSignature = signer.sign(signingKey, tamperedPolicyBase64);

        assertThat(tamperedPolicyBase64).isNotEqualTo(EXAMPLE_POLICY_BASE64);
        assertThat(tamperedSignature).isNotEqualTo(EXPECTED_SIGNATURE);
    }
}
