# distributed-cache-engine

[![standard-readme compliant](https://img.shields.io/badge/readme%20style-standard-brightgreen.svg?style=flat-square)](https://github.com/RichardLitt/standard-readme)
[![license: MIT](https://img.shields.io/badge/license-MIT-blue.svg?style=flat-square)](LICENSE)
[![version](https://img.shields.io/badge/version-0.1.0-blue.svg?style=flat-square)](https://github.com/nedder3/distributed-cache-engine/releases)
[![Java](https://img.shields.io/badge/Java-21+-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)

> High-throughput, distributed in-memory key-value cache engine featuring Hexagonal Architecture, CQRS, internal Event Sourcing, Vector Clocks with causal conflict resolution, advanced eviction strategies (W-TinyLFU with Count-Min Sketch), and hybrid disk persistence (Write-Ahead Log + Snapshots).

## Table of Contents

- [Background](#background)
- [Key Features](#key-features)
- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Module Structure](#module-structure)
- [Quick Start](#quick-start)
- [Eviction & Admission Policies](#eviction--admission-policies)
- [Replication & Conflict Resolution](#replication--conflict-resolution)
- [Contributing](#contributing)
- [License](#license)

---

## Background

A single-node in-memory cache is simple; real-world architectural challenges emerge when scaling to distributed topologies, ensuring eventual consistency, handling network partitions, and maintaining durability without sacrificing sub-millisecond read/write latency.

`distributed-cache-engine` is an enterprise-grade, distributed key-value cache engine built on modern Java 21 idioms (records, sealed hierarchies, virtual threads). It decouples operational concerns using Hexagonal Architecture and CQRS, persists writes via an append-only Write-Ahead Log (WAL) with CRC32 integrity verification, and provides active-active asynchronous replication with Vector Clock causality tracking.

---

## Key Features

- **Hexagonal Architecture (Ports & Adapters)**: Clean isolation between core caching domains, storage backends, replication transports, and eviction algorithms.
- **CQRS & Event Sourcing**: Explicit separation of mutation commands (`CommandHandler`) and read queries (`QueryHandler`), backed by an append-only event store and reactive `EventBus`.
- **Advanced Eviction & Frequency Estimation**:
  - `LRU` (Least Recently Used) & `LFU` (Least Frequently Used with FIFO tie-breaker).
  - `W-TinyLFU` (Window TinyLFU): Segmented LRU (Window + Probationary + Protected) with 4-bit `Count-Min Sketch` frequency filters and periodic half-life decay.
- **Hybrid Storage & Durability**:
  - In-memory hot indexing backed by `ConcurrentHashMap`.
  - Segmented `Write-Ahead Log` (WAL) with CRC32 payload verification, byte-level serializers, and deterministic crash-recovery replay.
  - Automated threshold-driven background snapshotting.
- **Active-Active Distributed Replication**:
  - Peer-to-peer asynchronous cluster synchronization.
  - Strict causal tracking via immutable `VectorClock`.
  - Deterministic conflict resolution combining causal dominance with Last-Write-Wins (LWW) tie-breaking.

---

## Architecture

```
                                  +-----------------------+
                                  |     Client / API      |
                                  +-----------+-----------+
                                              |
                                              v
                              +-------------------------------+
                              |      CacheEngine (Core)       |
                              +---------------+---------------+
                                              |
                     +------------------------+------------------------+
                     |                        |                        |
                     v                        v                        v
          +--------------------+   +--------------------+   +--------------------+
          |   CommandHandler   |   |    QueryHandler    |   |      EventBus      |
          |       (CQRS)       |   |       (CQRS)       |   | (Sync / Async Bus) |
          +---------+----------+   +----------+---------+   +---------+----------+
                    |                         |                       |
                    +------------+------------+                       |
                                 |                                    |
                                 v                                    v
     +------------------------------------------------------+  +---------------+
     |                   Hexagonal Ports                    |  |   Listeners   |
     |  (StoragePort, EvictionPort, ReplicationPort, etc.)  |  +---------------+
     +----+-------------------+-------------------+---------+
          |                   |                   |
          v                   v                   v
+-------------------+ +---------------+ +-------------------------------+
| HybridDiskStorage | |  W-TinyLFU /  | | ActiveActiveReplicationService|
|  (Memory + WAL)   | |  Count-Min    | |  (Vector Clocks / LWW Sync)   |
+-------------------+ +---------------+ +-------------------------------+
```

---

## Tech Stack

| Layer | Technology | Rationale |
|---|---|---|
| **Language** | Java 21+ | Sealed interfaces, immutable records, pattern matching |
| **Persistence** | Custom Binary WAL + Snapshots | Low-overhead durability, CRC32 checksums, replay recovery |
| **Concurrency** | Non-blocking data structures & Atomic primitives | High throughput, sub-millisecond latency |
| **Testing** | JUnit 5 + AssertJ + Mockito | 100% TDD test coverage across all layers |
| **Build Tool** | Apache Maven (Multi-module) | Modular compilation and packaging |

---

## Module Structure

```text
distributed-cache-engine/
├── cache-core/         # Domain records, ports, CQRS handlers, EventBus, eviction algorithms
├── cache-store/        # WAL engine, binary serializers, hybrid disk storage adapter
├── cache-replication/  # Dynamic membership, VectorClock conflict resolver, active-active sync
├── cache-server/       # Server bootstrap and high-level cache client builders
├── cache-benchmark/    # Performance harness and throughput benchmarks
└── diagramas/          # Mechanism and data-flow diagrams (.mmd)
```

---

## Quick Start

### Prerequisites
- JDK 21 or higher installed
- Apache Maven 3.9+

### Build and Test
```bash
# Clone the repository
git clone git@github.com:nedder3/distributed-cache-engine.git
cd distributed-cache-engine

# Run the complete test suite (359 tests)
mvn clean test

# Package all JAR artifacts
mvn clean package
```

### Usage Example

```java
import com.nedder3.cache.core.engine.CacheBuilder;
import com.nedder3.cache.core.engine.CacheEngine;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.port.WTinyLFUEvictionStrategy;

// 1. Build an in-memory cache engine with W-TinyLFU eviction
CacheEngine<String> cache = CacheBuilder.<String>create()
    .withCapacity(100_000)
    .withEvictionStrategy(new WTinyLFUEvictionStrategy<>(100_000))
    .build();

// 2. Perform CRUD operations with sub-millisecond latency
CacheKey key = CacheKey.of("user", "session_10492");
cache.put(key, "{ \"user_id\": 10492, \"role\": \"ADMIN\" }");

String sessionData = cache.get(key).orElse(null);
System.out.println("Session: " + sessionData);

// 3. Inspect metrics
System.out.println("Hits: " + cache.stats().hits() + ", Misses: " + cache.stats().misses());
```

---

## Eviction & Admission Policies

- **LRU (Least Recently Used)**: Evicts oldest accessed keys upon reaching configured capacity limits.
- **LFU (Least Frequently Used)**: Tracks absolute hit counts and breaks ties using insertion timestamps.
- **W-TinyLFU**: Incorporates a small LRU Window Cache (admitting 100% of new entries) and a segmented main cache (Probationary & Protected). Admissions between window and main regions are arbitrated by a 4-bit `Count-Min Sketch` frequency filter with periodic decay.

---

## Replication & Conflict Resolution

In multi-node active-active topologies, mutations carry an immutable `VectorClock`:
1. **Causal Dominance**: If event $A$ happened before event $B$ ($A \prec B$), version $B$ overwrites version $A$.
2. **Concurrent Mutations**: If events $A$ and $B$ are concurrent ($A \parallel B$), the engine resolves conflicts deterministically via Last-Write-Wins (LWW) by timestamp and node ID tie-breaking.

---

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting pull requests. All commits must follow Conventional Commits (`feat(distributed-cache-engine): ...`), and all code additions must adhere to the TDD standard.

---

## License

[MIT](LICENSE) © nedder3 (Ariel Jaime)
