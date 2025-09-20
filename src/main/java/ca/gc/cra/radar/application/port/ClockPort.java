package ca.gc.cra.radar.application.port;

public interface ClockPort {
  long nowMillis();

  ClockPort SYSTEM = System::currentTimeMillis;
}


