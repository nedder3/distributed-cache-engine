# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Conventional Commits](https://www.conventionalcommits.org/).

## [Unreleased]

### Added
- Hexagonal Architecture & In-Memory Core Engine (`cache-core`): immutable domain records, `VectorClock` with strict causality tracking, sealed `CacheEvent` hierarchy, reactive synchronous/asynchronous `EventBus`, and decoupled CQRS handlers (`CommandHandler` / `QueryHandler`).
- Advanced Eviction Strategies: `LRUEvictionPort`, `LFUEvictionStrategy`, and `WTinyLFUEvictionStrategy` with probabilistic `CountMinSketch` admission filter (4-bit counters with periodic halving/decay).
- Hybrid Persistence & Write-Ahead Log (`cache-store`): segmented `WriteAheadLog` with CRC32 payload checksums, deterministic crash-recovery replay, high-throughput binary serializers (`BinaryCacheEntrySerializer`), and `HybridDiskStorage` integrating disk snapshots with memory indexing.
- Distributed Active-Active Replication (`cache-replication`): dynamic `ClusterMembership` tracking, deterministic causal conflict resolution via `ConflictResolver` (Vector Clock dominance + Last-Write-Wins tie-breaking), and asynchronous peer replication service `ActiveActiveReplicationService`.
- Comprehensive TDD test suite with 359 unit and integration tests passing at 100%.

### Changed
- Updated `README.md`, `CONTRIBUTING.md`, `docs/CODE-DOCUMENTATION.md`, and `.gitignore` to match portfolio engineering standards.

- Initial repository scaffold and canonical documentation (2026-08-31).
- AGENTS.md, README.md, LICENSE (MIT).
