# TradingView Webhook Design Document

## Overview

This document outlines the design for implementing a TradingView webhook in the Autotrader application. The webhook will
receive alerts from TradingView and execute trades on the Bitvavo cryptocurrency exchange using the existing Bitvavo API
client.

## Architecture

For this application, a **modular monolithic architecture** is recommended. This approach offers several advantages:

1. **Simplicity**: A monolithic architecture is simpler to develop, deploy, and maintain compared to microservices.
2. **Lower operational complexity**: No need to manage inter-service communication, service discovery, or distributed
   transactions.
3. **Easier debugging**: Tracing issues through a single application is more straightforward than across multiple
   services.
4. **Modularity**: By organizing the code into well-defined modules, we maintain separation of concerns while avoiding
   the complexity of microservices.

The application will be structured into the following modules:

1. **Bitvavo API Module**: Contains the existing Bitvavo API client and related components.
2. **TradingView Webhook Module**: Handles incoming webhook requests from TradingView.
3. **Trading Service Module**: Contains the business logic for processing alerts and executing trades.
4. **Database Module**: Manages persistence of alerts and orders.

This modular approach allows for clear separation of concerns while maintaining the simplicity of a monolithic
application. If the application needs to scale in the future, these modules could be extracted into separate
microservices with minimal refactoring.

## Components

### 1. TradingView Webhook Endpoint

- **Path**: `/webhook/tradingview`
- **Method**: POST
- **Controller**: `TradingViewWebhookController`
- **Request Model**: `TradingViewAlertRequest`
  ```java
  public class TradingViewAlertRequest {
      private String botId;      // Alphanumeric identifier for the bot
      private String ticker;     // Symbol of the traded asset (e.g., "BTCEUR") - must match bot's configured ticker
      private String action;     // Type of order to place (e.g., "buy" or "sell")
      private String timestamp;  // UTC timestamp in format yyyy-MM-ddTHH:mm:ssZ
  }
  ```

  Note: In the first version, only EUR-based trading pairs are supported (e.g., "BTCEUR", "ETHEUR"). Each bot is
  configured for exactly one trading pair (ticker).
- **Response**: HTTP 200 OK on success, appropriate error codes on failure
- **Error Handling**:
    - 400 Bad Request: Invalid JSON or missing required fields
    - 400 Bad Request: Ticker mismatch (ticker doesn't match bot's configured ticker)
    - 400 Bad Request: Unsupported ticker (non-EUR based trading pairs are not supported in v1)
    - 404 Not Found: Bot ID not found
    - 500 Internal Server Error: Server-side errors

### 2. Trading Service

- **Service**: `TradingService`
- **Responsibilities**:
    - Validate incoming TradingView alerts
    - Look up bot configuration by botId
    - Process buy/sell signals according to business rules
    - Execute trades via the Bitvavo API client
    - Log alerts and orders to the database

### 3. Bot Configuration Service

- **Service**: `BotConfigurationService`
- **Responsibilities**:
    - Manage bot configurations (API keys, secrets, trading parameters)
    - Retrieve bot configuration by botId
    - Enforce ticker constraints (one ticker per bot, EUR-based tickers only)

### 4. Database Schema

#### MongoDB Collections

##### `tradingview_alerts` Collection

Stores all incoming alerts from TradingView.

```json
{
  "_id": "ObjectId",
  "botId": "String",
  "ticker": "String",
  "action": "String",
  "timestamp": "ISODate",
  "created_at": "ISODate"
}
```

##### `tradingview_orders` Collection

Stores orders placed on Bitvavo in response to TradingView alerts.

```json
{
  "_id": "ObjectId",
  "botId": "String",
  "order_id": "String",
  "ticker": "String",
  "timestamp": "ISODate",
  "status": "String",
  "error_message": "String",
  "created_at": "ISODate"
}
```

##### `bot_configurations` Collection

Stores bot configurations including API keys and secrets.

```json
{
  "_id": "ObjectId",
  "botId": "String",
  "apiKey": "String",
  "apiSecret": "String",
  "trading_pair": "String",
  "created_at": "ISODate",
  "updated_at": "ISODate"
}
```

The `trading_pair` field stores the single ticker (e.g., "BTCEUR") that the bot is configured to trade. In the first
version, only EUR-based tickers are supported.

##### `positions` Collection

Tracks open positions for each bot.

```json
{
  "_id": "ObjectId",
  "botId": "String",
  "ticker": "String",
  "status": "String",
  "created_at": "ISODate",
  "updated_at": "ISODate"
}
```

## Processing Flow

### Buy Signal Processing

1. Receive TradingView alert with "buy" action
2. Validate the alert data
3. Log the alert in `tradingview_alerts` collection
4. Look up bot configuration by botId
5. Verify that the ticker in the alert matches the bot's configured trading_pair
    - If mismatch, log error and return
6. Verify that the ticker is EUR-based (e.g., "BTCEUR")
    - If not EUR-based, log error and return
7. Check EUR balance using Bitvavo API
8. If balance < 5 EUR, log error and return
9. If balance ≥ 5 EUR, create market buy order
10. Log order result in `tradingview_orders` collection
11. Update position status in `positions` collection

### Sell Signal Processing

1. Receive TradingView alert with "sell" action
2. Validate the alert data
3. Log the alert in `tradingview_alerts` collection
4. Look up bot configuration by botId
5. Verify that the ticker in the alert matches the bot's configured trading_pair
    - If mismatch, log error and return
6. Verify that the ticker is EUR-based (e.g., "BTCEUR")
    - If not EUR-based, log error and return
7. Extract asset from ticker (e.g., "BTC" from "BTCEUR")
8. Check asset balance using Bitvavo API
9. Get asset price using Bitvavo API
10. Calculate asset worth in EUR
11. If worth < 5 EUR, log error and return
12. If worth ≥ 5 EUR, create market sell order
13. Log order result in `tradingview_orders` collection
14. Update position status in `positions` collection

## Error Handling

- All API calls should be wrapped in try-catch blocks
- Errors should be logged with appropriate context
- Failed orders should be recorded in the database with error messages
- The webhook should always return a response, even in error cases

## Security Considerations

### API Key and Secret Storage

To ensure that API keys and secrets are not stored as clear text in the database, the following encryption approach will
be implemented:

1. **Encryption at Rest**:
    - All sensitive data (API keys, secrets) will be encrypted before being stored in the database
    - AES-256 encryption in GCM mode will be used for strong security with authentication
    - A master encryption key will be stored securely outside the database (in environment variables or a secure vault)

2. **Key Management**:
    - The master encryption key will be rotated periodically (e.g., every 90 days)
    - Each bot configuration will have its own data encryption key (DEK)
    - The DEK will be encrypted with the master key (envelope encryption)

3. **Implementation Details**:
   ```java
   @Service
   public class EncryptionService {
       private final String masterKey;
       private final SecureRandom secureRandom;

       public EncryptionService(@Value("${encryption.master-key}") String masterKey) {
           this.masterKey = masterKey;
           this.secureRandom = new SecureRandom();
       }

       public String encrypt(String plaintext) {
           // Generate a random IV (Initialization Vector)
           byte[] iv = new byte[12]; // 96 bits for GCM
           secureRandom.nextBytes(iv);

           try {
               // Create cipher instance
               Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

               // Create secret key
               SecretKey key = new SecretKeySpec(
                   Base64.getDecoder().decode(masterKey), "AES");

               // Initialize cipher for encryption
               GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);
               cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);

               // Encrypt
               byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

               // Combine IV and ciphertext
               ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + ciphertext.length);
               byteBuffer.put(iv);
               byteBuffer.put(ciphertext);

               // Encode as Base64 string
               return Base64.getEncoder().encodeToString(byteBuffer.array());
           } catch (Exception e) {
               throw new RuntimeException("Encryption failed", e);
           }
       }

       public String decrypt(String ciphertext) {
           try {
               // Decode from Base64
               byte[] ciphertextBytes = Base64.getDecoder().decode(ciphertext);

               // Extract IV and ciphertext
               ByteBuffer byteBuffer = ByteBuffer.wrap(ciphertextBytes);
               byte[] iv = new byte[12];
               byteBuffer.get(iv);
               byte[] encrypted = new byte[byteBuffer.remaining()];
               byteBuffer.get(encrypted);

               // Create cipher instance
               Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

               // Create secret key
               SecretKey key = new SecretKeySpec(
                   Base64.getDecoder().decode(masterKey), "AES");

               // Initialize cipher for decryption
               GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);
               cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);

               // Decrypt
               byte[] decrypted = cipher.doFinal(encrypted);

               return new String(decrypted, StandardCharsets.UTF_8);
           } catch (Exception e) {
               throw new RuntimeException("Decryption failed", e);
           }
       }
   }
   ```

4. **Database Schema Changes**:
   The `bot_configurations` collection will be updated to store encrypted values:
   ```json
   {
     "_id": "ObjectId",
     "botId": "String",
     "encryptedApiKey": "String",
     "encryptedApiSecret": "String",
     "trading_pair": "String",
     "created_at": "ISODate",
     "updated_at": "ISODate",
     "key_version": "Integer"
   }
   ```

   The `key_version` field is used for key rotation support, allowing the system to track which encryption key version
   was used for each record.

5. **Service Layer**:
   ```java
   @Service
   public class BotConfigurationService {
       private final MongoTemplate mongoTemplate;
       private final EncryptionService encryptionService;

       // Constructor injection

       public void saveBotConfiguration(BotConfiguration config) {
           // Encrypt sensitive data
           String encryptedApiKey = encryptionService.encrypt(config.getApiKey());
           String encryptedApiSecret = encryptionService.encrypt(config.getApiSecret());

           // Create document with encrypted values
           Document document = new Document();
           document.put("botId", config.getBotId());
           document.put("encryptedApiKey", encryptedApiKey);
           document.put("encryptedApiSecret", encryptedApiSecret);
           document.put("trading_pair", config.getTicker());
           document.put("created_at", new Date());
           document.put("updated_at", new Date());
           document.put("key_version", 1);

           // Save to database
           mongoTemplate.save(document, "bot_configurations");
       }

       public BotConfiguration getBotConfiguration(String botId) {
           // Retrieve from database
           Document document = mongoTemplate.findOne(
               Query.query(Criteria.where("botId").is(botId)),
               Document.class,
               "bot_configurations"
           );

           if (document == null) {
               throw new NotFoundException("Bot configuration not found: " + botId);
           }

           // Decrypt sensitive data
           String apiKey = encryptionService.decrypt(document.getString("encryptedApiKey"));
           String apiSecret = encryptionService.decrypt(document.getString("encryptedApiSecret"));

           // Create and return configuration object
           return BotConfiguration.builder()
               .botId(document.getString("botId"))
               .apiKey(apiKey)
               .apiSecret(apiSecret)
               .ticker(document.getString("trading_pair"))
               .build();
       }
   }
   ```

### Webhook Authentication

Authentication for the webhook endpoint will be implemented using API keys:

1. **API Key Generation**:
    - Each bot will have a unique webhook API key
    - The API key will be generated using a secure random generator
    - The API key will be stored in the database (hashed, not encrypted)

2. **Authentication Process**:
   ```
   +-----------------+                  +------------------+
   | TradingView     |  POST /webhook   | Autotrader       |
   | Alert           +----------------->| Application      |
   | (with API Key)  |                  | (validates key)  |
   +-----------------+                  +------------------+
   ```

3. **Implementation Details**:
   ```java
   @Service
   public class WebhookAuthenticationService {
       private final MongoTemplate mongoTemplate;

       // Constructor injection

       public boolean validateApiKey(String botId, String apiKey) {
           // Retrieve bot configuration
           Document document = mongoTemplate.findOne(
               Query.query(Criteria.where("botId").is(botId)),
               Document.class,
               "bot_configurations"
           );

           if (document == null) {
               return false;
           }

           // Get stored hash
           String storedHash = document.getString("webhookKeyHash");

           // Hash the provided API key
           String providedHash = hashApiKey(apiKey);

           // Compare hashes
           return storedHash.equals(providedHash);
       }

       private String hashApiKey(String apiKey) {
           try {
               MessageDigest digest = MessageDigest.getInstance("SHA-256");
               byte[] hash = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
               return Base64.getEncoder().encodeToString(hash);
           } catch (NoSuchAlgorithmException e) {
               throw new RuntimeException("Hashing failed", e);
           }
       }
   }
   ```

4. **Controller Implementation**:
   ```java
   @RestController
   @RequestMapping("/webhook")
   public class TradingViewWebhookController {
       private final WebhookAuthenticationService authService;
       private final TradingService tradingService;

       // Constructor injection

       @PostMapping("/tradingview")
       public ResponseEntity<?> handleWebhook(
           @RequestHeader("X-API-KEY") String apiKey,
           @RequestBody TradingViewAlertRequest request
       ) {
           // Validate API key
           if (!authService.validateApiKey(request.getBotId(), apiKey)) {
               return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
           }

           // Process the alert
           tradingService.processAlert(request);

           return ResponseEntity.ok().build();
       }
   }
   ```

5. **Database Schema Changes**:
   The `bot_configurations` collection will be updated to store the webhook API key hash:
   ```json
   {
     "_id": "ObjectId",
     "botId": "String",
     "encryptedApiKey": "String",
     "encryptedApiSecret": "String",
     "trading_pair": "String",
     "webhookKeyHash": "String",
     "created_at": "ISODate",
     "updated_at": "ISODate",
     "key_version": "Integer"
   }
   ```

   The `webhookKeyHash` field stores the hashed webhook API key for authentication.

### Bot Creation Process

The process for creating a new bot with secure handling of API keys and secrets:

1. **API Endpoint**:
   ```java
   @RestController
   @RequestMapping("/bots")
   public class BotController {
       private final BotConfigurationService botConfigService;
       private final WebhookAuthenticationService webhookAuthService;

       // Constructor injection

       @PostMapping
       public ResponseEntity<BotCreationResponse> createBot(@RequestBody BotCreationRequest request) {
           // Validate request
           if (!isValidRequest(request)) {
               return ResponseEntity.badRequest().build();
           }

           // Generate webhook API key
           String webhookApiKey = generateSecureRandomKey();

           // Create bot configuration
           BotConfiguration config = BotConfiguration.builder()
               .botId(generateBotId())
               .apiKey(request.getApiKey())
               .apiSecret(request.getApiSecret())
               .ticker(request.getTicker())
               .build();

           // Save configuration (encrypts sensitive data)
           botConfigService.saveBotConfiguration(config);

           // Save webhook API key hash
           webhookAuthService.saveWebhookApiKey(config.getBotId(), webhookApiKey);

           // Return response with bot ID and webhook API key
           BotCreationResponse response = BotCreationResponse.builder()
               .botId(config.getBotId())
               .webhookApiKey(webhookApiKey)
               .build();

           return ResponseEntity.ok(response);
       }

       private String generateBotId() {
           return UUID.randomUUID().toString();
       }

       private String generateSecureRandomKey() {
           byte[] bytes = new byte[32];
           new SecureRandom().nextBytes(bytes);
           return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
       }

       private boolean isValidRequest(BotCreationRequest request) {
           // Validate API credentials
           boolean hasValidCredentials = request.getApiKey() != null && !request.getApiKey().isEmpty()
               && request.getApiSecret() != null && !request.getApiSecret().isEmpty();

           // Validate ticker
           boolean hasValidTicker = request.getTicker() != null && !request.getTicker().isEmpty();

           // Validate that ticker is EUR-based (e.g., "BTCEUR")
           boolean isEurBasedPair = hasValidTicker && request.getTicker().endsWith("-EUR");

           return hasValidCredentials && hasValidTicker && isEurBasedPair;
       }
   }
   ```

2. **Request/Response Models**:
   ```java
   @Data
   public class BotCreationRequest {
       private String apiKey;
       private String apiSecret;
       private String ticker;  // Single ticker this bot will trade (e.g., "BTCEUR")
       // Additional configuration parameters
   }

   @Data
   @Builder
   public class BotCreationResponse {
       private String botId;
       private String webhookApiKey;
       // Additional response data
   }
   ```

3. **Security Considerations**:
    - The API endpoint for bot creation should be secured (e.g., with OAuth2)
    - TLS should be used for all communications
    - API keys and secrets should never be logged
    - The webhook API key is only returned once during bot creation

### Additional Security Measures

1. **Input Validation**:
    - All input from TradingView alerts will be validated
    - JSON schema validation will be used to ensure proper structure
    - Input sanitization will be applied to prevent injection attacks

2. **Rate Limiting**:
    - Rate limiting will be implemented for the webhook endpoint
    - Limits will be set per bot ID to prevent abuse
    - Excessive requests will be rejected with a 429 Too Many Requests response

3. **Audit Logging**:
    - All authentication attempts will be logged
    - Failed authentication attempts will trigger alerts
    - Sensitive operations (e.g., bot creation) will be logged for audit purposes

4. **Secure Communications**:
    - All API endpoints will require HTTPS
    - TLS 1.2+ will be enforced
    - Proper certificate validation will be implemented

## Future Enhancements

1. **Support for Non-EUR Trading Pairs**: Extend the system to support tickers with base currencies other than EUR (
   e.g., USD-based pairs like BTCUSD)
2. **Multiple Exchange Support**: Extend the design to support other exchanges
3. **Advanced Order Types**: Support for limit orders, stop-loss, etc.
4. **Notification System**: Email/SMS alerts for important events
5. **Dashboard**: Web interface for monitoring and configuration
