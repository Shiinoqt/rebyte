package it.rebyte.user.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import it.rebyte.user.domain.DeveloperProfile;
import it.rebyte.user.domain.User;
import it.rebyte.user.dto.DeveloperProfileResponse;
import it.rebyte.user.dto.MeResponse;
import it.rebyte.user.dto.PublicProfileResponse;

/**
 * Mapping entita &lt;-&gt; DTO (D-66). {@code unmappedTargetPolicy = ERROR}:
 * un campo del DTO non popolato fa fallire la build invece di restituire
 * silenziosamente {@code null}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR,
        uses = AvatarUrlResolver.class)
public interface UserMapper {

    /**
     * {@code avatar} is assembled into a distribution URL at serialisation
     * time (D-33) — the column holds the bare key. The qualifier is
     * mandatory, not decorative: {@link AvatarUrlResolver#toPublicUrl} is a
     * {@code String -> String} method, so without {@code qualifiedByName}
     * pinning it to this one target it becomes a candidate for every other
     * String member too ({@code username}, {@code bio}, {@code location}).
     */
    @Mapping(target = "avatar", source = "avatar", qualifiedByName = "avatarKeyToUrl")
    MeResponse toMeResponse(User user);

    /**
     * Aggregates, follow state, {@code activeListings} and {@code isDeveloper}
     * are not entity fields (D-48 forbids storing them). They
     * are computed by {@link it.rebyte.user.service.UserProfileService} and
     * passed in as flat parameters, mapped explicitly here so {@code
     * unmappedTargetPolicy = ERROR} still catches a genuinely unpopulated
     * DTO field.
     */
    @Mapping(target = "sub", source = "user.id")
    @Mapping(target = "username", source = "user.username")
    @Mapping(target = "avatar", source = "user.avatar", qualifiedByName = "avatarKeyToUrl")
    @Mapping(target = "registration", source = "user.registration")
    @Mapping(target = "location", source = "user.location")
    @Mapping(target = "bio", source = "user.bio")
    @Mapping(target = "isDeveloper", source = "isDeveloper")
    @Mapping(target = "ratingAvg", source = "ratingAvg")
    @Mapping(target = "ratingCount", source = "ratingCount")
    @Mapping(target = "completedSales", source = "completedSales")
    @Mapping(target = "activeListings", source = "activeListings")
    @Mapping(target = "followerCount", source = "followerCount")
    @Mapping(target = "followingCount", source = "followingCount")
    @Mapping(target = "followedByMe", source = "followedByMe")
    PublicProfileResponse toPublicProfileResponse(
            User user, Double ratingAvg, long ratingCount, long completedSales,
            List<String> activeListings, boolean isDeveloper,
            long followerCount, long followingCount, boolean followedByMe);

    @Mapping(target = "sub", source = "userSub")
    DeveloperProfileResponse toDeveloperProfileResponse(DeveloperProfile developerProfile);
}
