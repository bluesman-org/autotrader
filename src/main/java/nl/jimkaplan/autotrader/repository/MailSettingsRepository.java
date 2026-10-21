package nl.jimkaplan.autotrader.repository;

import nl.jimkaplan.autotrader.model.document.MailSettings;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for the SMTP settings document. There is one record, stored under
 * {@link MailSettings#ID}.
 */
@Repository
public interface MailSettingsRepository extends MongoRepository<MailSettings, String> {
}
