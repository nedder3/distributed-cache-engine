# distributed-cache-engine

[![license: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![java](https://img.shields.io/badge/Java-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://www.java.com)

> Motor de caché clave-valor distribuido capaz de replicar datos de forma asíncrona entre múltiples nodos.

## Tabla de contenidos

- [Background](#background)
- [Features (planeadas)](#features-planeadas)
- [Stack tecnológico](#stack-tecnológico)
- [Quick Start](#quick-start)
- [Arquitectura](#arquitectura)
- [Contribuir](#contribuir)
- [Roadmap](#roadmap)
- [Licencia](#licencia)

## Background

Una caché single-node es trivial; el valor arquitectónico está en la replicación y consistencia entre nodos. Este componente aísla esa complejidad detrás de una API clave-valor simple.

## Features (planeadas)

- Núcleo funcional del patrón arquitectónico descrito arriba.
- API clara y desacoplada del framework.
- Cobertura de tests (TDD) del comportamiento central.

## Stack tecnológico

| Capa | Tecnología |
|------|-----------|
| Lenguaje | Java |
| Transporte | gRPC, TCP Sockets |
| Estructura | ConcurrentHashMap |
| Estrategias | Evicción LRU / LFU, replicación activa-activa |

## Quick Start

```
# TODO: por definir tras el primer scaffold de build (Maven/Gradle)
```

## Arquitectura

Implementa estrategias de desalojo avanzadas (LRU, LFU) y consistencia eventual mediante replicación activa-activa orientada a redes internas confiables. MVP single-node (ConcurrentHashMap + LRU/LFU) es días; el salto a distribuido (gRPC + replicación) es meses.

## Contribuir

Conventional commits con scope `distributed-cache-engine`. Ver [CONTRIBUTING.md](CONTRIBUTING.md).
Reglas clave: TDD antes de declarar hecho, JSDoc en símbolos públicos.

## Roadmap

- [ ] Definir build tooling (Maven/Gradle)
- [ ] Implementar MVP del núcleo
- [ ] Tests (TDD)
- [ ] Publicar en GitHub (repo `nedder3/distributed-cache-engine`)

## Licencia

[MIT](LICENSE) © nedder3 (Ariel Jaime)
