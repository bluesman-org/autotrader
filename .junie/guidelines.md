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
- **JUnit 5**: Testing framework
- **Mockito**: Mocking framework for testing
- **Spring RestDocs**: API documentation

## Project Structure

The project follows a standard Spring Boot application structure:

```
autotrader/
├── src/
│   ├── main/
│   │   ├── java/nl/jimkaplan/autotrader/
│   │   │   ├── client/         # API clients
│   │   │   ├── config/         # Configuration classes
│   │   │   ├── controller/     # REST controllers
│   │   │   ├── exception/      # Exception handling
│   │   │   ├── interceptor/    # HTTP interceptors
│   │   │   ├── model/          # Data models/DTOs
│   │   │   ├── service/        # Business logic services
│   │   │   ├── AutotraderApplication.java  # Main application class
│   │   │   └── ServletInitializer.java     # For WAR deployment
│   │   └── resources/
│   │       ├── application.yml  # Application configuration
│   │       ├── logback-spring.xml  # Logging configuration
│   │       ├── static/         # Static resources
│   │       └── templates/      # View templates
│   └── test/
│       └── java/nl/jimkaplan/autotrader/  # Test classes mirroring main structure
├── .mvn/                       # Maven wrapper files
├── logs/                       # Application logs
├── target/                     # Compiled output
├── mvnw                        # Maven wrapper script (Unix)
├── mvnw.cmd                    # Maven wrapper script (Windows)
├── pom.xml                     # Maven project configuration
└── README.md                   # Project documentation
```

## Running the Application

1. Set up the required environment variables:
   ```bash
   export BITVAVO_API_KEY=your-api-key
   export BITVAVO_API_SECRET=your-api-secret
   ```

2. Run the application using Maven wrapper:
   ```bash
   ./mvnw spring-boot:run
   ```

3. The application will be available at `http://localhost:8080`

## Testing

1. Run all tests:
   ```bash
   ./mvnw test
   ```

2. Run a specific test class:
   ```bash
   ./mvnw test -Dtest=BitvavoAuthenticationServiceTest
   ```

3. Run a specific test method:
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
    - Document APIs using Spring RestDocs
    - Handle exceptions properly using the GlobalExceptionHandler

3. **Testing**:
    - Write unit tests for all new code
    - Follow the existing test patterns (Arrange-Act-Assert)
    - Use mocks for external dependencies
    - Test both happy paths and error cases

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