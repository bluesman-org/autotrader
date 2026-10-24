package nl.jimkaplan.autotrader.repository;

import nl.jimkaplan.autotrader.strategy.model.document.PushSubscriptionDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * The browsers that subscribed to live Web Push notifications.
 */
@Repository
public interface PushSubscriptionRepository extends MongoRepository<PushSubscriptionDocument, String> {

    /**
     * @param endpoint The push service URL the browser returned
     * @return The subscription for that endpoint, when it exists
     */
    Optional<PushSubscriptionDocument> findByEndpoint(String endpoint);

    /**
     * @param endpoint The push service URL the browser returned
     */
    void deleteByEndpoint(String endpoint);
}
