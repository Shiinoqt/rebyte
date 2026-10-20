package it.rebyte.catalog.dto;

public record ArtifactUploadPolicyResponse(String url, String key, String algorithm, String credential,
        String date, String policy, String signature, String securityToken) {
}
