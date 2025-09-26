/**
 * Core domain model for RADAR capture → assemble → sink pipelines.
 * <p><strong>Role:</strong> Domain layer aggregates describing flows, messages, and protocols without infrastructure dependencies.</p>
 * <p><strong>Concurrency:</strong> Types are immutable unless noted; safe to share across threads.</p>
 * <p><strong>Performance:</strong> Designed for allocation-efficient copies when streaming high-volume traffic.</p>
 * <p><strong>Metrics:</strong> Domain attributes feed tagging on {@code capture.*}, {@code assemble.*}, and {@code sink.*} metrics.</p>
 * <p><strong>Security:</strong> Payload-bearing types require downstream redaction and access controls.</p>
 */
package ca.gc.cra.radar.domain;
