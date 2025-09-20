package ca.gc.cra.radar.infrastructure.time;

import ca.gc.cra.radar.application.port.ClockPort;

public final class SystemClockAdapter implements ClockPort {
  @Override
  public long nowMillis() {
    return System.currentTimeMillis();
  }
}


