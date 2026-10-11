// Runs once, on first initialization of the local MongoDB data directory.
// Creates the application user for the "autotrader" database.
// The password comes from the container environment (see docker-compose.local.yml).
db = db.getSiblingDB('autotrader');
db.createUser({
  user: 'autotrader',
  pwd: process.env.MONGO_APP_PASSWORD,
  roles: [{ role: 'readWrite', db: 'autotrader' }],
});
