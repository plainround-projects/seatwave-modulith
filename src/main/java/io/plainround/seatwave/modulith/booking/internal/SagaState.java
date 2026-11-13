package io.plainround.seatwave.modulith.booking.internal;

/**
 * Module 28's eight saga states, kept by the booking module in {@code booking.booking_sagas}. In
 * one deployable several of them live only inside one local transaction (the confirm walks
 * SEAT_HELD to PAYMENT_REQUESTED in T1, and PAYMENT_SUCCEEDED to COMPLETED in T2), but each is a
 * real row state that a crash, or a saga carried over from Module 28's services, can leave behind
 * for the sweeper.
 */
enum SagaState {
  SEAT_HELD,
  PAYMENT_REQUESTED,
  PAYMENT_SUCCEEDED,
  PAYMENT_FAILED,
  REFUND_REQUESTED,
  PAYMENT_REFUNDED,
  COMPLETED,
  COMPENSATED;

  /** COMPLETED or COMPENSATED: nothing moves the saga again. */
  boolean isFinal() {
    return this == COMPLETED || this == COMPENSATED;
  }
}
