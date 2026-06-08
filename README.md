# Loyalty Points System

Backend for a retail loyalty program: customers earn points on purchases, redeem them for
rewards, and are assigned a tier based on spending — with points expiring 12 months after
they're earned.

> **Status:** scaffold only. Right now the app boots and serves a `/health` check. The
> loyalty endpoints and data model are being built next.

## Tech stack

- **Java 21**
- **[Javalin](https://javalin.io/)** — lightweight HTTP framework (embedded Jetty)
- **SQLite** — embedded, file-based database (via `sqlite-jdbc`)
- **Maven** — build tool

## Prerequisites

You need **JDK 21** and **Maven**. On macOS with [Homebrew](https://brew.sh/):

```bash
brew install openjdk@21 maven
```

## Running it

From the project root (`loyalty-points/`):

```bash
# 1. Point this shell at JDK 21 (Homebrew installs it "keg-only", so we set JAVA_HOME
#    explicitly. Maven otherwise defaults to whatever other JDK is on the system.)
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

# 2. Build (compiles, runs tests, produces a runnable jar in target/)
mvn clean package

# 3. Run
java -jar target/loyalty-points.jar
```

You should see Javalin/Jetty start up and log:

```
Started ServerConnector ... 0.0.0.0:7070
```

The server is now listening on **http://localhost:7070**. Leave it running and open a second
terminal for the next step.

> **Tip:** `mvn exec:java` runs the app without building a jar — handy during development.
> And `PORT=8080 java -jar target/loyalty-points.jar` starts it on a different port.

## Verifying it works

In a second terminal:

```bash
curl http://localhost:7070/health
```

Expected response:

```json
{"status":"ok"}
```

To stop the server, press `Ctrl+C` in the terminal where it's running.

## Design

_(To be written — will cover the data model, a key trade-off, what we'd add with more time,
and a note on AI tool usage.)_
