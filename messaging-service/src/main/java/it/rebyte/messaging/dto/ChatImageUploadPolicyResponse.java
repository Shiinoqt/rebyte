package it.rebyte.messaging.dto;

public record ChatImageUploadPolicyResponse(String url, String key, String algorithm, String credential,
        String date, String policy, String signature, String securityToken, String contentType) {
}
