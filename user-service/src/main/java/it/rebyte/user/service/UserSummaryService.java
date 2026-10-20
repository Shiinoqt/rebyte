package it.rebyte.user.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import it.rebyte.user.dto.UserSummary;
import it.rebyte.user.error.InvalidUserBatchException;
import it.rebyte.user.repository.DeveloperProfileRepository;
import it.rebyte.user.repository.SellerReviewRepository;
import it.rebyte.user.repository.SellerSaleRepository;
import it.rebyte.user.repository.UserRepository;

/**
 * The internal user-summary read (D-51, D-52). Reused by
 * {@link it.rebyte.user.controller.internal.UserInternalController}, the
 * one contract Phases 2 through 5 consume for seller reputation, buyer and
 * seller lookups and display names.
 *
 * <p><b>Total, never a 404 (D-52).</b> For a never-provisioned {@code sub}
 * this method SYNTHESIZES a minimal summary rather than provisioning one:
 * {@code /internal/**} is unauthenticated by design, so a write side
 * effect here would let any caller or retry loop create rows for arbitrary
 * identifiers. Because the generated handle
 * ({@link UserService#generateHandle(String)}) is a pure function of the
 * sub, and {@code ratingAvg}/{@code ratingCount}/{@code isDeveloper} are
 * computed the identical way regardless of whether a {@code user} row
 * exists (an aggregate over {@code seller_review} and an existence check
 * on {@code developer_profile}, neither gated on the user row's
 * existence), the synthesized summary is byte-identical to the row
 * just-in-time provisioning would create for the same sub — this is
 * exactly {@link it.rebyte.user.service.UserProfileService
 * #getPublicProfile(String, String)}'s own synthesis pattern, reused here for the
 * internal contract.
 */
@Service
public class UserSummaryService {

    private static final int MAX_BATCH_SIZE = 50;

    private final UserRepository userRepository;
    private final SellerReviewRepository sellerReviewRepository;
    private final DeveloperProfileRepository developerProfileRepository;
    private final SellerSaleRepository sellerSaleRepository;

    public UserSummaryService(
            UserRepository userRepository,
            SellerReviewRepository sellerReviewRepository,
            DeveloperProfileRepository developerProfileRepository,
            SellerSaleRepository sellerSaleRepository) {
        this.userRepository = userRepository;
        this.sellerReviewRepository = sellerReviewRepository;
        this.developerProfileRepository = developerProfileRepository;
        this.sellerSaleRepository = sellerSaleRepository;
    }

    public UserSummary getSummary(String sub) {
        long ratingCount = sellerReviewRepository.countBySellerSub(sub);
        Double ratingAvg = ratingCount > 0 ? sellerReviewRepository.averageRatingBySellerSub(sub) : null;
        long completedSales = sellerSaleRepository.countCompletedSales(sub);
        boolean isDeveloper = developerProfileRepository.existsById(sub);

        return userRepository.findById(sub)
                .map(user -> new UserSummary(user.getId(), user.getUsername(), user.getAvatar(), isDeveloper,
                        ratingAvg, ratingCount, completedSales))
                .orElseGet(() -> new UserSummary(sub, UserService.generateHandle(sub), null, isDeveloper,
                        ratingAvg, ratingCount, completedSales));
    }

    public List<UserSummary> getSummaries(List<String> requestedSubs) {
        LinkedHashSet<String> distinctSubs = new LinkedHashSet<>();
        for (String requestedSub : requestedSubs) {
            String sub = requestedSub.trim();
            if (!sub.isEmpty()) {
                distinctSubs.add(sub);
            }
        }

        if (distinctSubs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subs must contain a non-blank value");
        }
        if (distinctSubs.size() > MAX_BATCH_SIZE) {
            throw new InvalidUserBatchException();
        }
        List<UserSummary> summaries = new ArrayList<>(distinctSubs.size());
        for (String sub : distinctSubs) {
            summaries.add(getSummary(sub));
        }
        return summaries;
    }
}
