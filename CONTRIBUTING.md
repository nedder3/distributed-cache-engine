# Contributing

Reglas para contribuir a este proyecto (aplica a Cy y a humanos).

## Commits

Formato [Conventional Commits](https://www.conventionalcommits.org/) con **scope** del proyecto:

```
feat(distributed-cache-engine): agregar bus de eventos asíncrono
fix(distributed-cache-engine): corregir race condition en el pool de hilos
docs(distributed-cache-engine): actualizar README
test(distributed-cache-engine): cubrir edge case de encolado
chore: actualizar dependencias
```

Tipos: `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `chore`.
`BREAKING CHANGE:` en el body para cambios mayores.

## TDD

Tests antes de declarar hecho. Verificar con `hermes verify`.

## Documentación de código

Todo símbolo público lleva Javadoc. Ver [`docs/CODE-DOCUMENTATION.md`](docs/CODE-DOCUMENTATION.md).

## Diagramas

Cy genera los diagramas en `diagramas/` (mermaid). No son artefactos sueltos: viven en el vault.
