/**
 * <strong>Purpose:</strong> Validation helpers used during CLI parsing and configuration bootstrap.
 * <p><strong>Pipeline role:</strong> Domain support for the capture and assemble stages; ensures invalid
 * inputs are rejected before ports/adapters allocate network or file resources.
 * <p><strong>Concurrency:</strong> Stateless utilities safe for concurrent invocation.
 * <p><strong>Performance:</strong> Branch-only checks; no allocations beyond intermediate strings.
 * <p><strong>Observability:</strong> No direct metrics or logging; failures surface via {@link IllegalArgumentException}.
 * <p><strong>Security:</strong> Enforces printable ASCII constraints to avoid control character injection and
 * malformed topic names.
 *
 * @since 0.1.0
 */
package ca.gc.cra.radar.validation;
