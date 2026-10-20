package it.rebyte.catalog.dto;

/** POST these fields to url, followed by Content-Type and the file as the last form field. */
public record PhotoUploadPolicyResponse(String url, String key, String algorithm, String credential,
        String date, String policy, String signature, String securityToken) {
}
