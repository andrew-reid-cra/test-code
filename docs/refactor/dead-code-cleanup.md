# Dead Code Cleanup

## Removed
- `target/site/apidocs/ca/gc/cra/radar/infrastructure/persistence/LegacySegmentPersistenceAdapter.html`, `target/site/apidocs/ca/gc/cra/radar/infrastructure/persistence/class-use/LegacySegmentPersistenceAdapter.html`, `target/site/apidocs/src-html/ca/gc/cra/radar/infrastructure/persistence/LegacySegmentPersistenceAdapter.html` - stale generated pages for the deleted adapter; removing them keeps the published API site aligned with current sources.

## Refactored/Consolidated
- `src/main/java/ca/gc/cra/radar/config/CaptureConfig.java` - deprecated the unused `fromArgs(String[])` shim so all CLIs now rely on the canonical `fromMap` path until the method is removed in RADAR 0.2.0.

## Deferred (Deprecated)
- `src/main/java/ca/gc/cra/radar/infrastructure/protocol/http/legacy/LegacyHttpAssembler.java` - kept as a throwing shim for binary compatibility; callers should migrate to `ca.gc.cra.radar.application.pipeline.PosterUseCase`. Scheduled removal remains RADAR 0.2.0.

## Safety Checks
- **Reference scan**
  ```
  $ jdeps --verbose:class target/classes | Select-String 'LegacySegmentPersistenceAdapter'
  (no matches)
  ```
- **Grep verification**
  ```
  $ rg "LegacySegmentPersistenceAdapter" src
  (no matches)
  
  $ rg "CaptureConfig\.fromArgs" --glob '!**/CaptureConfig.java' src
  (no matches)
  ```
- **Build & tests**
  ```
  $ mvn -q test
  WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
  WARNING: sun.misc.Unsafe::staticFieldBase has been called by com.google.inject.internal.aop.HiddenClassDefiner (file:/d:/maven/lib/guice-5.1.0-classes.jar)
  WARNING: Please consider reporting this to the maintainers of class com.google.inject.internal.aop.HiddenClassDefiner
  WARNING: sun.misc.Unsafe::staticFieldBase will be removed in a future release
  SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder".
  SLF4J: Defaulting to no-operation (NOP) logger implementation
  SLF4J: See http://www.slf4j.org/codes.html#StaticLoggerBinder for further details.

  $ mvn -q '-DskipTests=false' '-Dspotbugs.skip=false' '-Dcheckstyle.skip=false' verify
  WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
  WARNING: sun.misc.Unsafe::staticFieldBase has been called by com.google.inject.internal.aop.HiddenClassDefiner (file:/d:/maven/lib/guice-5.1.0-classes.jar)
  WARNING: Please consider reporting this to the maintainers of class com.google.inject.internal.aop.HiddenClassDefiner
  WARNING: sun.misc.Unsafe::staticFieldBase will be removed in a future release
  SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder".
  SLF4J: Defaulting to no-operation (NOP) logger implementation
  SLF4J: See http://www.slf4j.org/codes.html#StaticLoggerBinder for further details.
  ```
