# Migrating Distributed Lock 2.x to 3.x

## Runtime Assembly

2.x used `BackendModule` objects:

```java
LockRuntimeBuilder.create()
    .backend("redis")
    .backendModules(List.of(new RedisBackendModule(configuration)))
    .build();
```

3.x uses explicit providers and typed configuration:

```java
LockRuntimeBuilder.create()
    .backend("redis")
    .backendProvider(new RedisBackendProvider())
    .backendConfiguration(configuration)
    .build();
```

Runtime assembly is intentionally explicit. The builder does not auto-select a backend from classpath contents.

## Runtime Metadata

Replace `runtime.capabilities()` with `runtime.info().behavior()`.

`RuntimeInfo` also exposes the selected backend id, backend display name, and runtime version without requiring application code to import SPI types.

## Lock Context

`LockContext` is no longer in the core API. Use the `lease` parameter inside `LockedAction`:

```java
lockExecutor.withLock(request, lease -> use(lease.fencingToken()));
```

Applications that require synchronous annotation-only context access should add `distributed-lock-extension-context` and use `LockScopeContext`.

## Spring Boot

The generic starter collects `BackendProvider<?>` and backend configuration beans. Backend-specific modules create those beans. `distributed.lock.backend` remains required.

The generic starter does not contain Redis or ZooKeeper bean names and does not own backend-specific properties.

## Backend Authors

Backend authors now implement `BackendProvider<C>`, `BackendClient`, `BackendSession`, and `BackendLease`, and depend on `distributed-lock-api` plus `distributed-lock-spi`.

Backend behavior metadata is described with `BackendBehavior` and exposed through `BackendDescriptor<C>`.
