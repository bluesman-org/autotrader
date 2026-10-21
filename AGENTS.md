# Autotrader Project Guidelines

This document provides guidelines for developers working on the Autotrader project.

## Project Overview

Autotrader is a Spring Boot application that trades on the Bitvavo cryptocurrency exchange. Its
strategy engine — own MACD and Parabolic SAR implementations, configurable entry/exit rules, a
train/validate backtester and a live runner that is dry-run by default — is the primary path. The
older TradingView webhook integration is kept as a legacy fallback (see Phase 6 in PLAN.md). The
application also provides a REST API for interacting with the exchange.

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
│   │   │   ├── strategy/                   # In-app strategy engine
│   │   │   │   ├── model/                  # Candle intervals and documents
│   │   │   │   └── service/                # Candle history loader
│   │   │   └── tradingview/                # TradingView webhook integration (legacy fallback)
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

3. The application will be available at `http://localhost:8081/autotrader` — the UI at
   `/autotrader/ui/backtests` and the API docs at `/autotrader/swagger-ui.html`

### Local MongoDB

For local development, `docker-compose.local.yml` starts a MongoDB in Docker. It reads
`MONGO_ROOT_PASSWORD` and `MONGO_APP_PASSWORD` from the repo-root `.env` file, which is
gitignored.

```bash
docker compose -f docker-compose.local.yml up -d
export SPRING_DATA_MONGODB_URI="mongodb://autotrader:<MONGO_APP_PASSWORD>@127.0.0.1:27017/autotrader"
export MONGODB_SSL_ENABLED=false
export ENCRYPTION_MASTER_KEY="$(grep -m1 '^ENCRYPTION_MASTER_KEY=' .env | cut -d= -f2-)"
```

### Local Docker development stack

`docker-compose.dev.yml` runs the app and that MongoDB together — it includes
`docker-compose.local.yml` and adds the app container. The image copies `target/autotrader.jar`, so
build the jar first:

```bash
./mvnw -B package -DskipTests
docker compose -f docker-compose.dev.yml up -d --build
```

It reads `MONGO_ROOT_PASSWORD`, `MONGO_APP_PASSWORD` and `ENCRYPTION_MASTER_KEY` from the
repo-root `.env`; nothing loads it for a `spring-boot:run` run, so the loop shares the key and
the database only while it exports that same value (as in the example above). With the same key
on both sides, bots and exchange credentials stay readable through either path. The UI is served
on `http://127.0.0.1:8081/autotrader/ui/backtests` — the published port is bound to the loopback,
since the API takes HTTP Basic and the pages a session (see `docs/security.md`). On the box that
serves `https://at.bluris.nl`, `.env` sets `SESSION_COOKIE_SECURE=true` and
`AUTOTRADER_PUBLIC_URL=https://at.bluris.nl/autotrader`, and the pages sign in over https there.
`./mvnw spring-boot:run` remains the faster edit/run loop (devtools restarts the app on recompiles);
the container is the stable one.

The `Dockerfile` is platform-agnostic: the target platform is chosen at build time — native for
the local build, and `-Ddocker.platforms=linux/arm64` (buildx) for the Docker workflow's portable
arm64 build. This box is the production host: the container from `docker-compose.dev.yml` is what
`https://at.bluris.nl` serves.

Historical candles are cached in this database. They are excluded from
`scripts/backup-mongo.sh` because they can be re-downloaded from Bitvavo; the nightly dumps
are also copied offsite to a mounted Google Drive — see `docs/backups.md`. Indexes are created
at startup by `MongoIndexInitializer`, explicitly, so that the duplicate-`botId` check runs
before the unique index and reports an actionable error. `MongoConfig` honors
`spring.data.mongodb.auto-index-creation`; it is off by default (and not set in
`application.yml`) — enabling it creates the annotation-declared indexes during repository
setup instead, before that check can run.

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
    - Minimum 95% line coverage, 100% method coverage and 85% branch coverage, except POJOs and
      similar classes. All LLM assistants must follow this rule.
    - Static analysis is part of the gate: SpotBugs (with FindSecBugs) runs at `verify` and must
      report nothing outside `config/spotbugs-exclude.xml`, where every accepted finding carries a
      reason. The coverage workflow runs on pull requests and on main pushes.
    - `AutotraderApplicationTests` starts the full application context, so a bean that cannot be
      wired fails in CI instead of in a container run. The Docker image is built whenever the
      Dockerfile, compose files or pom change (`.github/workflows/docker.yml`).

4. **Security**:
    - Never commit API keys or secrets to version control
    - Always use environment variables for sensitive information
    - Follow the authentication pattern used for Bitvavo API
    - The app holds one account (see `docs/security.md`): the pages sign in with a form and a
      session, the API with HTTP Basic, and a fresh installation starts at `/signup`. The
      TradingView webhook stays open because it authenticates itself, everything else — pages, API,
      Swagger UI — needs a sign-in. A page asks with a redirect to `/login`; an API request answers
      401. Recovery mail goes out through the SMTP server stored on the settings page.
    - Production is served on `https://at.bluris.nl` behind Caddy, with
      `server.forward-headers-strategy=native` (Tomcat trusts forwarded addresses only from a proxy
      on the host, which is what makes the exporter's loopback rule safe) and
      `SESSION_COOKIE_SECURE=true` with `SameSite=Strict`, so redirects and cookies keep the https
      scheme. The site block and the deployment checklist are in
      `docs/security.md`; the host change is the operator's.

5. **Bitvavo HTTP clients**:
    - `restTemplate` is used for signed trading calls and deliberately has no socket timeouts: a read
      timeout on an order submission is not a safe failure (the order is recorded as `FAILED` while the
      exchange may have accepted it, and a retried alert could duplicate it).
    - `publicRestTemplate` serves public market data (candle backfills) with bounded connect and read
      timeouts. Keep this split when adding new Bitvavo calls.

6. **GitHub Actions**:
    - Use the latest major version of every action when a workflow is created or updated
      (currently `actions/checkout@v7`, `actions/setup-java@v6`, `docker/setup-qemu-action@v4`),
      and never pin an action to a moving branch such as `@master` or `@main`.

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

## API Documentation

The application provides a REST API for interacting with the Bitvavo cryptocurrency exchange. The API is documented
using OpenAPI/Swagger.

### Swagger UI

You can explore the API documentation using the Swagger UI at:

```
http://localhost:8081/autotrader/swagger-ui.html
```

### Strategy backtest UI

The in-app Thymeleaf UI for configuring and ranking strategies is at:

```
http://localhost:8081/autotrader/ui/backtests
```

It drives the same backtest API (`POST /api/backtests`): set the market, interval and range, the
simulation settings, the indicator candidate grids and the entry/exit rule sets, then browse the
ranked train/validate results and the recent runs.

### Postman Integration

The application automatically generates OpenAPI specification files that can be imported into Postman:

- **JSON Format**: `http://localhost:8081/autotrader/api-docs/autotrader-openapi.json`
- **YAML Format**: `http://localhost:8081/autotrader/api-docs/autotrader-openapi.yaml`

These files are updated automatically whenever the application starts or when endpoints change, ensuring they always
reflect the latest API endpoints.

#### Importing into Postman

1. Open Postman
2. Click on "Import" in the top left corner
3. Select "Link" tab
4. Enter the URL to the YAML or JSON file (e.g., `http://localhost:8081/autotrader/api-docs/autotrader-openapi.yaml`)
5. Click "Continue" and then "Import"

This will create a collection in Postman with all the API endpoints defined in the application.
