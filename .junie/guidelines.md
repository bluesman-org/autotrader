# Autotrader Project Guidelines

This document provides guidelines for developers working on the Autotrader project.

## Project Overview

Autotrader is a Spring Boot application that implements automated trading with TradingView and Bitvavo. It provides a
REST API for interacting with the Bitvavo cryptocurrency exchange.

## Tech Stack

- **Java 21**: The project uses Java 21 as the programming language
- **Spring Boot 3.4.4**: Framework for building web applications
- **Maven**: Build and dependency management tool
- **Lombok**: Reduces boilerplate code
- **MongoDB**: NoSQL database for data storage
- **JUnit 5**: Testing framework
- **Mockito**: Mocking framework for testing
- **OpenAPI/Swagger**: API documentation and testing via springdoc-openapi
- **AES-256 Encryption**: For securing sensitive data like API keys

## Project Structure

The project follows a standard Spring Boot application structure:

```
autotrader/
├── src/
│   ├── main/
│   │   ├── java/nl/jimkaplan/autotrader/
│   │   │   ├── AutotraderApplication.java  # Main application class
│   │   │   ├── ServletInitializer.java     # For WAR deployment
│   │   │   ├── bitvavo/                    # Bitvavo integration
│   │   │   │   ├── client/                 # Bitvavo API clients
│   │   │   │   ├── model/                  # Bitvavo data models
│   │   │   │   └── service/                # Bitvavo services
│   │   │   ├── config/                     # Application configuration
│   │   │   ├── controller/                 # Common REST controllers
│   │   │   ├── exception/                  # Exception handling
│   │   │   ├── interceptor/                # HTTP interceptors
│   │   │   ├── model/                      # Common data models
│   │   │   ├── repository/                 # Data repositories
│   │   │   ├── service/                    # Business logic services
│   │   │   └── tradingview/                # TradingView integration
│   │   │       ├── controller/             # TradingView REST controllers
│   │   │       ├── model/                  # TradingView data models
│   │   │       └── service/                # TradingView services
│   │   └── resources/
│   │       ├── application.yml             # Application configuration
│   │       └── logback-spring.xml          # Logging configuration
│   └── test/
│       └── java/nl/jimkaplan/autotrader/   # Test classes mirroring main structure
├── .mvn/                                   # Maven wrapper files
├── logs/                                   # Application logs
├── target/                                 # Compiled output
├── mvnw                                    # Maven wrapper script (Unix)
├── mvnw.cmd                                # Maven wrapper script (Windows)
├── pom.xml                                 # Maven project configuration
└── README.md                               # Project documentation
```

## Running the Application

1. Set up the required environment variables:
   ```bash
   export MONGODB_AUTOTRADER_URI=your-mongodb-uri
   export MONGODB_AUTOTRADER_USER=your-mongodb-user
   export MONGODB_AUTOTRADER_PASSWORD=your-mongodb-password
   export BITVAVO_API_URL=https://api.bitvavo.com/v2
   export ENCRYPTION_MASTER_KEY=your-base64-encoded-encryption-key
   ```

   Alternatively, you can create a `.env` file in the project root with these variables:
   ```
   MONGODB_AUTOTRADER_URI=your-mongodb-uri
   MONGODB_AUTOTRADER_USER=your-mongodb-user
   MONGODB_AUTOTRADER_PASSWORD=your-mongodb-password
   BITVAVO_API_URL=https://api.bitvavo.com/v2
   ENCRYPTION_MASTER_KEY=your-base64-encoded-encryption-key
   ```

   Note: The Bitvavo API key and secret are not set as environment variables. Instead, they are stored in the MongoDB
   database in encrypted form using the ENCRYPTION_MASTER_KEY. You can configure them through the application's API.

2. Run the application using Maven wrapper:
   ```bash
   ./mvnw spring-boot:run
   ```

3. The application will be available at `http://localhost:8080`

## Testing

1. Test Environment Setup:
   The project uses a separate test environment configuration:
    - `src/test/resources/.env.test` - Contains test values for environment variables
    - `src/test/resources/application-test.yml` - Test-specific application configuration

   The test environment is automatically activated when running tests with the `test` profile.

2. Run all tests:
   ```bash
   ./mvnw test
   ```

3. Run a specific test class:
   ```bash
   ./mvnw test -Dtest=BitvavoAuthenticationServiceTest
   ```

4. Run a specific test method:
   ```bash
   ./mvnw test -Dtest=BitvavoAuthenticationServiceTest#createAuthHeaders_withValidConfig_returnsHeaders
   ```

Tests follow the Arrange-Act-Assert pattern and use Mockito for mocking dependencies.

## Best Practices

1. **Code Organization**:
    - Follow the existing package structure
    - Keep classes focused on a single responsibility
    - Use appropriate layers (controller, service, client)

2. **API Development**:
    - Follow RESTful principles
    - Document APIs using OpenAPI/Swagger annotations
    - Handle exceptions properly using the GlobalExceptionHandler

3. **Testing**:
    - Write unit tests for all new code
    - Follow the existing test patterns (Arrange-Act-Assert)
    - Use mocks for external dependencies
    - Test both happy paths and error cases
    - Aim for having 100% line coverage. If not possible, do not go below 90%

4. **Security**:
    - Never commit API keys or secrets to version control
    - Always use environment variables for sensitive information
    - Follow the authentication pattern used for Bitvavo API

## Development Workflow

1. **Setup**:
    - Clone the repository
    - Set up required environment variables
    - Build the project with `./mvnw clean install`

2. **Development**:
    - Create a feature branch
    - Implement changes
    - Write tests
    - Run tests locally
    - Update documentation if needed

3. **Code Review**:
    - Submit a pull request
    - Address review comments
    - Ensure all tests pass
    - Merge after approval
