package ca.gc.cra.radar.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NumbersTest {

  @Test
  void requireRangeReturnsValueWithinBounds() {
    assertEquals(10, Numbers.requireRange("workers", 10, 1, 64));
  }

  @Test
  void requireRangeRejectsValuesBelowMinimum() {
    assertThrows(IllegalArgumentException.class, () -> Numbers.requireRange("workers", 0, 1, 64));
  }

  @Test
  void requireRangeRejectsValuesAboveMaximum() {
    assertThrows(IllegalArgumentException.class, () -> Numbers.requireRange("workers", 65, 1, 64));
  }
}
