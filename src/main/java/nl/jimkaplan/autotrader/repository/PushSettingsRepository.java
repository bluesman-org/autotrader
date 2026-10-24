package nl.jimkaplan.autotrader.repository;

import nl.jimkaplan.autotrader.strategy.model.document.PushSettings;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * The singleton Web Push key material (see {@link PushSettings}).
 */
@Repository
public interface PushSettingsRepository extends MongoRepository<PushSettings, String> {
}
