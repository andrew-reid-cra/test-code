package ca.gc.cra.radar.config;

public enum IoMode {
  FILE,
  KAFKA;

  public static IoMode fromString(String value) {
    if (value == null || value.isBlank()) {
      return FILE;
    }
    try {
      return IoMode.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown ioMode: " + value);
    }
  }
}
