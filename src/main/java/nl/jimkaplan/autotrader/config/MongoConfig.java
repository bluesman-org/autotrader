package nl.jimkaplan.autotrader.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.AbstractMongoClientConfiguration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.lang.NonNull;

import java.util.concurrent.TimeUnit;

/**
 * MongoDB configuration for the application.
 * This class configures the MongoDB connection and enables MongoDB repositories.
 */
@Configuration
@EnableMongoRepositories(basePackages = "nl.jimkaplan.autotrader.repository")
public class MongoConfig extends AbstractMongoClientConfiguration {

    @Value("${spring.data.mongodb.uri}")
    private String mongoUri;

    @Value("${spring.data.mongodb.database}")
    private String databaseName;

    @NonNull
    @Override
    public String getDatabaseName() {
        return databaseName;
    }

    @NonNull
    @Override
    public MongoClient mongoClient() {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(mongoUri))
                .applyToClusterSettings(builder ->
                        builder.serverSelectionTimeout(10, TimeUnit.SECONDS))
                .applyToSocketSettings(builder ->
                        builder.connectTimeout(10, TimeUnit.SECONDS)
                                .readTimeout(15, TimeUnit.SECONDS))
                .applyToConnectionPoolSettings(builder ->
                        builder.maxSize(50)
                                .minSize(10)
                                .maxWaitTime(15, TimeUnit.SECONDS))
                .retryWrites(true)
                .retryReads(true)
                .readPreference(ReadPreference.secondaryPreferred())
                .writeConcern(WriteConcern.MAJORITY)
                .applyToSslSettings(builder -> builder.enabled(true))
                .build();

        return MongoClients.create(settings);
    }
}