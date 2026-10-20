package it.rebyte.user.dto;

/**
 * {@code POST /users/me/avatar/upload-policy} response (USER-03, D-34
 * AMENDED). The client performs a normal {@code multipart/form-data} POST
 * to {@code url}, sending every field below as a form field, PLUS
 * {@code Content-Type} (matching {@code image/*}) and the file itself.
 *
 * <p><b>The {@code file} field MUST be the last form field in the
 * multipart body</b> — Amazon S3 requires this and ignores any field that
 * comes after it, per AWS's own POST-upload documentation. This is a
 * client-side ordering requirement this DTO cannot enforce; it is
 * documented here because a violation fails silently (the extra fields are
 * dropped, not rejected).
 *
 * <p>{@code securityToken} is {@code null} when the resolved credentials
 * are not session/STS credentials (D-34) — omit the
 * {@code X-Amz-Security-Token} form field entirely in that case, rather
 * than sending an empty one.
 */
public record AvatarUploadPolicyResponse(
        String url,
        String key,
        String algorithm,
        String credential,
        String date,
        String policy,
        String signature,
        String securityToken) {
}
