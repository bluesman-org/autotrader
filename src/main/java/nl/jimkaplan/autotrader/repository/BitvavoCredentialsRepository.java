package nl.jimkaplan.autotrader.repository;

import nl.jimkaplan.autotrader.model.document.BitvavoCredentials;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for the system-wide Bitvavo credentials document. There is one record, stored under
 * {@link BitvavoCredentials#ID}.
 */
@Repository
public interface BitvavoCredentialsRepository extends MongoRepository<BitvavoCredentials, String> {
}
