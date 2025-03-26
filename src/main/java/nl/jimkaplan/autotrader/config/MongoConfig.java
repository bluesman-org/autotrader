package nl.jimkaplan.autotrader.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.AbstractMongoClientConfiguration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * MongoDB configuration for the application.
 * This class configures the MongoDB connection and enables MongoDB repositories.
 */
@Configuration
@EnableMongoRepositories(basePackages = "nl.jimkaplan.autotrader.repository")
public class MongoConfig extends AbstractMongoClientConfiguration {

    @Value("${spring.data.mongodb.uri:mongodb+srv://localhost:27017/autotrader}")
    private String mongoUri;

    @Value("${spring.data.mongodb.database:autotrader}")
    private String databaseName;

    @Override
    public String getDatabaseName() {
        return databaseName;
    }

    @Override
    public MongoClient mongoClient() {
        return MongoClients.create(mongoUri);
    }
}