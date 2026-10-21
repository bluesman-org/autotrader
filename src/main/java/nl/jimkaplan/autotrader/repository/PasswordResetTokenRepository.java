package nl.jimkaplan.autotrader.repository;

import nl.jimkaplan.autotrader.model.document.PasswordResetToken;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * The password-recovery tokens, keyed by the account's id so a new request replaces the old token.
 */
@Repository
public interface PasswordResetTokenRepository extends MongoRepository<PasswordResetToken, String> {

    /**
     * @param tokenHash The SHA-256 hash of the token the link carried
     * @return The token document with that hash, when it exists
     */
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);
}
