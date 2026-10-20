package it.rebyte.user.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.rebyte.user.domain.FollowId;
import it.rebyte.user.domain.User;
import it.rebyte.user.dto.PublicProfileResponse;
import it.rebyte.user.dto.UpdateProfileRequest;
import it.rebyte.user.error.ReservedHandleException;
import it.rebyte.user.error.UsernameConflictException;
import it.rebyte.user.mapper.UserMapper;
import it.rebyte.user.repository.DeveloperProfileRepository;
import it.rebyte.user.repository.FollowRepository;
import it.rebyte.user.repository.SellerReviewRepository;
import it.rebyte.user.repository.SellerSaleRepository;
import it.rebyte.user.repository.UserRepository;

/**
 * Owner-scoped profile editing (USER-02, D-57, D-58, D-61) and the public
 * profile read (USER-04, D-06, D-48, D-50).
 */
@Service
public class UserProfileService {

    private final UserRepository userRepository;
    private final UsernameRules usernameRules;
    private final SellerReviewRepository sellerReviewRepository;
    private final UserMapper userMapper;
    private final DeveloperProfileRepository developerProfileRepository;
    private final SellerSaleRepository sellerSaleRepository;
    private final FollowRepository followRepository;

    public UserProfileService(
            UserRepository userRepository,
            UsernameRules usernameRules,
            SellerReviewRepository sellerReviewRepository,
            UserMapper userMapper,
            DeveloperProfileRepository developerProfileRepository,
            SellerSaleRepository sellerSaleRepository,
            FollowRepository followRepository) {
        this.userRepository = userRepository;
        this.usernameRules = usernameRules;
        this.sellerReviewRepository = sellerReviewRepository;
        this.userMapper = userMapper;
        this.developerProfileRepository = developerProfileRepository;
        this.sellerSaleRepository = sellerSaleRepository;
        this.followRepository = followRepository;
    }

    /**
     * Mutable fields are exactly {@code username}, {@code location} and
     * {@code bio} (D-57): {@code id} is immutable (it is the {@code sub}),
     * {@code registration} is final, there is no password here, {@code
     * avatar} goes through plan 01-05's upload flow, and developer
     * activation is USER-05's own endpoint. {@link UpdateProfileRequest}
     * carries no {@code id}/{@code registration}/{@code avatar} members, so
     * a mass-assignment attempt has nothing to bind to.
     *
     * <p>On rename, applies {@link UsernameRules} then attempts the write
     * directly — does NOT pre-check existence and then write, because the
     * unique index is the authority and a check-then-write races (D-58).
     * The unique-key violation is caught here and translated into a {@link
     * UsernameConflictException}.
     *
     * <p>No rename cooldown in this phase; the gateway's per-IP rate limit
     * is the only throttle (D-61).
     */
    @Transactional
    public User updateProfile(User user, UpdateProfileRequest request) {
        String normalized = usernameRules.normalizeAndValidate(request.username());
        if (usernameRules.isReservedShape(normalized)) {
            throw new ReservedHandleException(
                    "username '" + normalized + "' matches the reserved generated-handle shape");
        }

        user.setUsername(normalized);
        user.setLocation(request.location());
        user.setBio(request.bio());

        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException conflict) {
            throw new UsernameConflictException(normalized);
        }
    }

    /**
     * Public read (USER-04, D-06). No ownership check — {@code /users/me}
     * remains the only owner-scoped surface (D-55). A {@code sub} with no
     * row is served from the same deterministic shape rather than a 404
     * (D-06, D-52's synthesis pattern applied to the public endpoint too):
     * a profile row orphaned by a Cognito-side deletion is accepted as
     * documented dead data, and there is no way to distinguish "never
     * signed up" from "deleted in Cognito" without querying Cognito, which
     * this service never does.
     *
     * <p>{@code isDeveloper} reflects the real {@code developer_profile}
     * row (USER-05). Follow counts are independent of profile provisioning;
     * the caller's follow state is computed per request.
     */
    public PublicProfileResponse getPublicProfile(String sub, String callerSub) {
        long ratingCount = sellerReviewRepository.countBySellerSub(sub);
        Double ratingAvg = ratingCount > 0 ? sellerReviewRepository.averageRatingBySellerSub(sub) : null;
        long completedSales = sellerSaleRepository.countCompletedSales(sub);
        boolean isDeveloper = developerProfileRepository.existsById(sub);
        long followerCount = followRepository.countFollowers(sub);
        long followingCount = followRepository.countFollowing(sub);
        boolean followedByMe = callerSub != null && !callerSub.equals(sub)
                && followRepository.existsById(new FollowId(callerSub, sub));

        return userRepository.findById(sub)
                .map(user -> userMapper.toPublicProfileResponse(
                        user, ratingAvg, ratingCount, completedSales, List.of(), isDeveloper,
                        followerCount, followingCount, followedByMe))
                .orElseGet(() -> synthesizePublicProfile(sub, ratingAvg, ratingCount, completedSales, isDeveloper,
                        followerCount, followingCount, followedByMe));
    }

    private PublicProfileResponse synthesizePublicProfile(
            String sub, Double ratingAvg, long ratingCount, long completedSales, boolean isDeveloper,
            long followerCount, long followingCount, boolean followedByMe) {
        return new PublicProfileResponse(
                sub, UserService.generateHandle(sub), null, null, null, null,
                isDeveloper, ratingAvg, ratingCount, completedSales, List.of(),
                followerCount, followingCount, followedByMe);
    }
}
