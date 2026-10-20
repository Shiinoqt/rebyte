package it.rebyte.messaging.service;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.domain.Message;
import it.rebyte.messaging.dto.ConversationPage;
import it.rebyte.messaging.dto.MessagePage;
import it.rebyte.messaging.error.ConversationNotFoundException;
import it.rebyte.messaging.error.InvalidCursorException;
import it.rebyte.messaging.realtime.PresenceService;
import it.rebyte.messaging.repository.ConversationRepository;

@Service
public class ConversationQueryService {

    private final MongoTemplate mongo;
    private final ConversationRepository conversations;
    private final UsernameLookup usernames;
    private final ConversationViews conversationViews;
    private final MessageViews messageViews;
    private final ReadService reads;
    private final PresenceService presence;

    public ConversationQueryService(MongoTemplate mongo, ConversationRepository conversations,
            UsernameLookup usernames, ConversationViews conversationViews, MessageViews messageViews,
            ReadService reads, PresenceService presence) {
        this.mongo = mongo;
        this.conversations = conversations;
        this.usernames = usernames;
        this.conversationViews = conversationViews;
        this.messageViews = messageViews;
        this.reads = reads;
        this.presence = presence;
    }

    /** D-39: the caller's conversations as buyer or seller, newest activity first, one username batch per page. */
    public ConversationPage list(String callerSub, String cursor, Integer requestedSize) {
        int size = Cursors.pageSize(requestedSize);
        Cursors.InstantIdCursor before = Cursors.decodeInstantId(cursor);
        Criteria participant = new Criteria().orOperator(Criteria.where("buyerSub").is(callerSub),
                Criteria.where("sellerSub").is(callerSub));
        Criteria criteria = before == null ? participant : new Criteria().andOperator(participant,
                new Criteria().orOperator(Criteria.where("lastMessageAt").lt(before.at()),
                        Criteria.where("lastMessageAt").is(before.at()).and("_id").lt(before.id())));
        Query query = Query.query(criteria).with(Sort.by(Sort.Direction.DESC, "lastMessageAt", "_id"))
                .limit(size + 1);
        List<Conversation> rows = mongo.find(query, Conversation.class);
        List<Conversation> page = rows.subList(0, Math.min(rows.size(), size));
        List<String> partners = page.stream().map(conversation -> conversation.otherParticipant(callerSub)).toList();
        Map<String, String> names = usernames.usernames(partners);
        Map<String, Boolean> online = presence.onlineMap(partners);
        String nextCursor = null;
        if (rows.size() > size) {
            Conversation last = page.getLast();
            nextCursor = Cursors.encodeInstantId(last.getLastMessageAt(), last.getId());
        }
        return new ConversationPage(page.stream()
                .map(conversation -> conversationViews.of(conversation, callerSub,
                        names.get(conversation.otherParticipant(callerSub)),
                        online.getOrDefault(conversation.otherParticipant(callerSub), false)))
                .toList(), nextCursor);
    }

    /**
     * D-39 history newest first by seq. Only the newest page (no cursor) marks messages read (D-38), and only
     * up to the highest seq on that page.
     */
    public MessagePage messages(String callerSub, String conversationId, String cursor, Integer requestedSize) {
        Conversation conversation = conversations.findById(conversationId)
                .filter(found -> found.isParticipant(callerSub))
                .orElseThrow(ConversationNotFoundException::new);
        // A present but empty cursor is a client bug, not a request for the newest page that would mark messages read.
        if (cursor != null && cursor.isBlank()) {
            throw new InvalidCursorException();
        }
        int size = Cursors.pageSize(requestedSize);
        Long before = Cursors.decodeSeq(cursor);
        Criteria criteria = Criteria.where("conversationId").is(conversationId);
        if (before != null) {
            criteria.and("seq").lt(before);
        }
        List<Message> rows = mongo.find(Query.query(criteria).with(Sort.by(Sort.Direction.DESC, "seq"))
                .limit(size + 1), Message.class);
        List<Message> page = rows.subList(0, Math.min(rows.size(), size));
        String nextCursor = rows.size() > size ? Cursors.encodeSeq(page.getLast().getSeq()) : null;
        if (before == null && !page.isEmpty()) {
            reads.markRead(callerSub, conversation, ReadService.newestSeq(page));
        }
        return new MessagePage(page.stream().map(messageViews::of).toList(), nextCursor);
    }
}
