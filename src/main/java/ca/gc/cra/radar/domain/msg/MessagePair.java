package ca.gc.cra.radar.domain.msg;

/** Correlated request/response pair produced by the pairing engine. */
public record MessagePair(MessageEvent request, MessageEvent response) {}


