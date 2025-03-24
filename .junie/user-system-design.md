# User System Design Document

## Overview

This document outlines the design for implementing a user system in the Autotrader application. The user system will
allow users to create accounts, authenticate, and manage their trading bots. Each user can have 1 to 100 bots associated
with their account.

## User Model

### User Document

The User document will be stored in MongoDB and will contain the following fields:

```json
{
  "_id": "ObjectId",
  "username": "String",
  "email": "String",
  "passwordHash": "String",
  "firstName": "String",
  "lastName": "String",
  "active": "Boolean",
  "role": "String",
  "lastLogin": "ISODate",
  "created_at": "ISODate",
  "updated_at": "ISODate"
}
```

#### Field Descriptions:

- **_id**: MongoDB's default document identifier
- **username**: Unique username for the user (required)
- **email**: User's email address (required, unique)
- **passwordHash**: Hashed password using BCrypt (required)
- **firstName**: User's first name (optional)
- **lastName**: User's last name (optional)
- **active**: Whether the user account is active (default: true)
- **role**: User's role (e.g., "USER", "ADMIN") (default: "USER")
- **lastLogin**: Timestamp of the user's last login
- **created_at**: Timestamp when the user was created
- **updated_at**: Timestamp when the user was last updated

### Java Implementation

```java

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Document(collection = "users")
public class User extends BaseDocument {

    @Indexed(unique = true)
    @Field("username")
    private String username;

    @Indexed(unique = true)
    @Field("email")
    private String email;

    @Field("passwordHash")
    private String passwordHash;

    @Field("firstName")
    private String firstName;

    @Field("lastName")
    private String lastName;

    @Field("active")
    private Boolean active = true;

    @Field("role")
    private String role = "USER";

    @Field("lastLogin")
    private Instant lastLogin;

    // Transient field not stored in the database
    private transient String password;
}
```

## User-Bot Relationship

### Approach

The relationship between users and bots will be implemented by adding a `userId` field to the `BotConfiguration`
document. This approach has several advantages:

1. **Simplicity**: It's straightforward to implement and understand
2. **Query Efficiency**: Easy to query all bots belonging to a user
3. **Scalability**: Works well with MongoDB's document model

### BotConfiguration Updates

The `BotConfiguration` document will be updated to include a reference to the user:

```json
{
  "_id": "ObjectId",
  "botId": "String",
  "userId": "String",
  "encryptedApiKey": "String",
  "encryptedApiSecret": "String",
  "trading_pair": "String",
  "webhookKeyHash": "String",
  "key_version": "Integer",
  "active": "Boolean",
  "created_at": "ISODate",
  "updated_at": "ISODate"
}
```

The `userId` field is a reference to the user who owns this bot.

### Java Implementation Updates

```java

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Document(collection = "bot_configurations")
public class BotConfiguration extends BaseDocument {

    @Indexed(unique = true)
    @Field("botId")
    private String botId;

    @Indexed
    @Field("userId")
    private String userId;  // New field to reference the user

    @Field("encryptedApiKey")
    private String encryptedApiKey;

    @Field("encryptedApiSecret")
    private String encryptedApiSecret;

    @Field("trading_pair")
    private String tradingPair;

    @Field("webhookKeyHash")
    private String webhookKeyHash;

    @Field("key_version")
    private Integer keyVersion;

    @Field("active")
    private Boolean active = true;

    // Transient fields not stored in the database
    private transient String apiKey;
    private transient String apiSecret;
}
```

## Bot Limit Enforcement

To enforce the limit of 100 bots per user, the `BotConfigurationService` will be updated to check the number of bots a
user already has before creating a new one:

```java
public BotConfiguration saveBotConfiguration(BotConfiguration config) {
    // Check if user has reached the bot limit
    long userBotCount = botConfigurationRepository.countByUserId(config.getUserId());
    if (userBotCount >= 100) {
        throw new BotLimitExceededException("User has reached the maximum limit of 100 bots");
    }

    // Encrypt sensitive data and save the bot configuration
    // ... (existing code)
}
```

## Authentication and Authorization

### Authentication

The user system will use Spring Security with JWT (JSON Web Tokens) for authentication:

1. **Login Flow**:
    - User submits username/email and password
    - System validates credentials
    - If valid, system generates a JWT token
    - Token is returned to the client
    - Client includes token in subsequent requests

2. **JWT Token**:
    - Contains user ID, username, and roles
    - Signed with a secret key
    - Has an expiration time

### Authorization

Authorization will be role-based:

1. **Roles**:
    - USER: Can manage their own bots
    - ADMIN: Can manage all bots and users

2. **Access Control**:
    - Users can only access and modify their own bots
    - Admins can access and modify any bot
    - Certain endpoints are restricted to admins only

## API Endpoints

### User Management

#### Registration

- **Path**: `/api/users/register`
- **Method**: POST
- **Request Body**:
  ```json
  {
    "username": "string",
    "email": "string",
    "password": "string",
    "firstName": "string",
    "lastName": "string"
  }
  ```
- **Response**: 201 Created with user details (excluding password)

#### Login

- **Path**: `/api/auth/login`
- **Method**: POST
- **Request Body**:
  ```json
  {
    "username": "string",
    "password": "string"
  }
  ```
- **Response**: 200 OK with JWT token
  ```json
  {
    "token": "string",
    "expiresIn": "number"
  }
  ```

#### Get User Profile

- **Path**: `/api/users/profile`
- **Method**: GET
- **Headers**: Authorization: Bearer {token}
- **Response**: 200 OK with user details

#### Update User Profile

- **Path**: `/api/users/profile`
- **Method**: PUT
- **Headers**: Authorization: Bearer {token}
- **Request Body**:
  ```json
  {
    "email": "string",
    "firstName": "string",
    "lastName": "string"
  }
  ```
- **Response**: 200 OK with updated user details

#### Change Password

- **Path**: `/api/users/password`
- **Method**: PUT
- **Headers**: Authorization: Bearer {token}
- **Request Body**:
  ```json
  {
    "currentPassword": "string",
    "newPassword": "string"
  }
  ```
- **Response**: 200 OK

### Bot Management (Updated)

#### Create Bot

- **Path**: `/api/bots`
- **Method**: POST
- **Headers**: Authorization: Bearer {token}
- **Request Body**:
  ```json
  {
    "apiKey": "string",
    "apiSecret": "string",
    "tradingPair": "string"
  }
  ```
- **Response**: 201 Created with bot details and webhook API key

#### Get User's Bots

- **Path**: `/api/bots`
- **Method**: GET
- **Headers**: Authorization: Bearer {token}
- **Response**: 200 OK with list of user's bots

#### Get Bot Details

- **Path**: `/api/bots/{botId}`
- **Method**: GET
- **Headers**: Authorization: Bearer {token}
- **Response**: 200 OK with bot details

#### Update Bot

- **Path**: `/api/bots/{botId}`
- **Method**: PUT
- **Headers**: Authorization: Bearer {token}
- **Request Body**:
  ```json
  {
    "apiKey": "string",
    "apiSecret": "string",
    "tradingPair": "string",
    "active": "boolean"
  }
  ```
- **Response**: 200 OK with updated bot details

#### Delete Bot

- **Path**: `/api/bots/{botId}`
- **Method**: DELETE
- **Headers**: Authorization: Bearer {token}
- **Response**: 204 No Content

## Service Layer Updates

### UserService

```java

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public User registerUser(User user) {
        // Check if username or email already exists
        if (userRepository.existsByUsername(user.getUsername())) {
            throw new UsernameAlreadyExistsException("Username already exists");
        }
        if (userRepository.existsByEmail(user.getEmail())) {
            throw new EmailAlreadyExistsException("Email already exists");
        }

        // Hash the password
        user.setPasswordHash(passwordEncoder.encode(user.getPassword()));
        user.setPassword(null); // Clear the transient password field
        user.setActive(true);
        user.setRole("USER");

        // Save the user
        return userRepository.save(user);
    }

    public Optional<User> getUserById(String id) {
        return userRepository.findById(id);
    }

    public Optional<User> getUserByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public User updateUser(User user) {
        // Update user details
        return userRepository.save(user);
    }

    public void changePassword(String userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        // Verify current password
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new InvalidPasswordException("Current password is incorrect");
        }

        // Update password
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    public void updateLastLogin(String userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setLastLogin(Instant.now());
            userRepository.save(user);
        });
    }
}
```

### BotConfigurationService Updates

```java

@Service
@RequiredArgsConstructor
public class BotConfigurationService {
    private final BotConfigurationRepository botConfigurationRepository;
    private final EncryptionService encryptionService;

    // New method to get bots by user ID
    public List<BotConfiguration> getBotsByUserId(String userId) {
        return botConfigurationRepository.findByUserIdAndActive(userId, true).stream()
                .map(this::decryptSensitiveData)
                .toList();
    }

    // New method to count bots by user ID
    public long countBotsByUserId(String userId) {
        return botConfigurationRepository.countByUserId(userId);
    }

    // Updated method to save bot configuration with user ID check
    public BotConfiguration saveBotConfiguration(BotConfiguration config) {
        // Check if user has reached the bot limit
        long userBotCount = botConfigurationRepository.countByUserId(config.getUserId());
        if (userBotCount >= 100) {
            throw new BotLimitExceededException("User has reached the maximum limit of 100 bots");
        }

        // Encrypt sensitive data
        String encryptedApiKey = encryptionService.encrypt(config.getApiKey());
        String encryptedApiSecret = encryptionService.encrypt(config.getApiSecret());

        // Set encrypted values
        config.setEncryptedApiKey(encryptedApiKey);
        config.setEncryptedApiSecret(encryptedApiSecret);
        config.setKeyVersion(1); // Initial key version

        // Clear transient fields
        config.setApiKey(null);
        config.setApiSecret(null);

        // Save to database
        return botConfigurationRepository.save(config);
    }

    // Updated method to get bot configuration with user ID check
    public Optional<BotConfiguration> getBotConfiguration(String botId, String userId) {
        return botConfigurationRepository.findByBotIdAndUserIdAndActive(botId, userId, true)
                .map(this::decryptSensitiveData);
    }

    // For admin use only
    public Optional<BotConfiguration> getBotConfigurationByAdmin(String botId) {
        return botConfigurationRepository.findByBotIdAndActive(botId, true)
                .map(this::decryptSensitiveData);
    }

    // Other methods remain the same but with user ID checks added where appropriate
}
```

## Security Configuration

```java

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final UserDetailsService userDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf().disable()
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/api/users/register").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
```

## Database Indexes

To ensure optimal performance, the following indexes will be created:

1. **users collection**:
    - username (unique)
    - email (unique)

2. **bot_configurations collection**:
    - botId (unique)
    - userId (non-unique)
    - userId + active (compound)

## Future Enhancements

1. **Two-Factor Authentication**: Add support for 2FA using TOTP (Time-based One-Time Password)
2. **Social Login**: Allow users to sign in with Google, GitHub, etc.
3. **User Groups**: Support for organizing users into groups with shared access to bots
4. **Subscription Tiers**: Different user tiers with varying bot limits and features
5. **Audit Logging**: Comprehensive logging of all user actions for security and compliance
6. **User Notifications**: Email or in-app notifications for important events (bot status, trades, etc.)
7. **User Dashboard**: Web interface for users to monitor and manage their bots
