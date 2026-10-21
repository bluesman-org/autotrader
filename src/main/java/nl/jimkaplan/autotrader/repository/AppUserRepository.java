package nl.jimkaplan.autotrader.repository;

import nl.jimkaplan.autotrader.model.document.AppUser;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * The application's accounts. There is one in normal use; the signup page closes once the first
 * exists (the unique index on the username enforces the rest).
 */
@Repository
public interface AppUserRepository extends MongoRepository<AppUser, String> {

    /**
     * @param username The login name, lowercased
     * @return The account with that name, when it exists
     */
    Optional<AppUser> findByUsername(String username);

    /**
     * @param email The recovery address, lowercased
     * @return The account with that address, when it exists
     */
    Optional<AppUser> findByEmail(String email);
}
