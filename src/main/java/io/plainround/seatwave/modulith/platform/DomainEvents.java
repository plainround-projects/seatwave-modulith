package io.plainround.seatwave.modulith.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-process publication of domain events between modules. A publisher names no subscriber, so the
 * dependency points from the listening module to the publishing one, never back.
 *
 * <p>Delivery is synchronous, on the publisher's thread: a listener runs inside the publisher's
 * transaction, and a listener that throws rolls that transaction back. That is the monolith's
 * strength (one commit for the change and its reactions) and the thing that splits when a listening
 * module leaves the deployable. Thread-safety: safe for concurrent use.
 */
public final class DomainEvents {

  private final List<Subscription<?>> subscriptions = new CopyOnWriteArrayList<>();

  /** Registers a listener for one event type; listeners run in registration order. */
  public <E> void subscribe(Class<E> eventType, Consumer<? super E> listener) {
    subscriptions.add(
        new Subscription<>(
            Objects.requireNonNull(eventType, "eventType"),
            Objects.requireNonNull(listener, "listener")));
  }

  /** Delivers the event to every listener of its type, now, on this thread. */
  public void publish(Object event) {
    Objects.requireNonNull(event, "event");
    List<Subscription<?>> matching = new ArrayList<>();
    for (Subscription<?> subscription : subscriptions) {
      if (subscription.eventType().isInstance(event)) {
        matching.add(subscription);
      }
    }
    matching.forEach(subscription -> subscription.deliver(event));
  }

  private record Subscription<E>(Class<E> eventType, Consumer<? super E> listener) {

    void deliver(Object event) {
      listener.accept(eventType.cast(event));
    }
  }
}
