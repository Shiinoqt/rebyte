package it.rebyte.catalog.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.SoftwareListing;
import it.rebyte.catalog.domain.Version;
import it.rebyte.catalog.dto.ReleaseVersionRequest;
import it.rebyte.catalog.error.DuplicateVersionNumberException;
import it.rebyte.catalog.error.InvalidVersionNumberException;
import it.rebyte.catalog.error.ListingNotFoundException;
import it.rebyte.catalog.error.ListingNotOwnedException;
import it.rebyte.catalog.error.VersionNumberNotIncreasingException;
import it.rebyte.catalog.messaging.ListingChangedEvent;
import it.rebyte.catalog.messaging.VersionReleasedEvent;
import it.rebyte.catalog.outbox.DomainEventRecorder;
import it.rebyte.commons.messaging.RebyteExchanges;

@Service
public class VersionReleaseService {
    private final MongoTemplate mongo;
    private final DomainEventRecorder events;
    private final TransactionTemplate transactions;

    public VersionReleaseService(MongoTemplate mongo, DomainEventRecorder events, PlatformTransactionManager tm) {
        this.mongo = mongo;
        this.events = events;
        this.transactions = new TransactionTemplate(tm);
    }

    /**
     * Appends a release. Versions have no edit or delete path because a licence
     * grants access to every release, including those published after purchase.
     */
    public Listing release(String id, ReleaseVersionRequest request, String sellerSub) {
        if (mongo.findById(id, Listing.class) == null) throw new ListingNotFoundException(id);
        return transactions.execute(status -> {
            Listing listing = mongo.findById(id, Listing.class);
            if (listing == null) throw new ListingNotFoundException(id);
            if (!listing.getSellerSub().equals(sellerSub)) throw new ListingNotOwnedException(id);
            SoftwareListing software = listing.getSoftware();
            if (software == null) throw new InvalidVersionNumberException(request.number());
            VersionNumbers.compare(request.number(), request.number());
            for (Version version : software.getVersions()) {
                if (VersionNumbers.compare(version.getNumber(), request.number()) == 0) {
                    throw new DuplicateVersionNumberException(request.number());
                }
            }
            if (VersionNumbers.compare(request.number(), software.currentVersion().getNumber()) <= 0) {
                throw new VersionNumberNotIncreasingException(request.number());
            }
            Version released = new Version(request.number(), Instant.now(), request.notes(), null);
            Listing saved = mongo.findAndModify(Query.query(where("id").is(id).and("sellerSub").is(sellerSub)),
                    new Update().push("software.versions", released),
                    FindAndModifyOptions.options().returnNew(true), Listing.class);
            if (saved == null) throw new ListingNotOwnedException(id);
            long seq = saved.getVersion() == null ? 0 : saved.getVersion();
            Instant occurredAt = Instant.now();
            events.record(UUID.randomUUID().toString(), RebyteExchanges.LISTING_UPDATED,
                    new ListingChangedEvent(saved.getId(), seq, occurredAt), occurredAt);
            events.record(UUID.randomUUID().toString(), RebyteExchanges.LISTING_VERSION_RELEASED,
                    new VersionReleasedEvent(saved.getId(), sellerSub, request.number(), saved.getTitle()), occurredAt);
            return saved;
        });
    }

    public Listing get(String id) {
        Listing listing = mongo.findById(id, Listing.class);
        if (listing == null) throw new ListingNotFoundException(id);
        return listing;
    }
}
