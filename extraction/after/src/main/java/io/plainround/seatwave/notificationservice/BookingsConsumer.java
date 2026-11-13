package io.plainround.seatwave.notificationservice;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * The notification service's reader of {@code seatwave.bookings}: Module 27's consumer group {@code
 * notifications-email}, at least once. It hands each message to {@link NotificationService} and
 * commits the offsets only after the messages were handled, so a crash between the two delivers
 * them again, which the service's claim absorbs. It reads every partition of the topic, assigned
 * rather than subscribed: one reader, no rebalance. Not thread-safe: one thread polls.
 */
public final class BookingsConsumer implements AutoCloseable {

  /** Module 27's topic of booking events. */
  public static final String TOPIC = "seatwave.bookings";

  // A short pause between two questions to the broker about leaders; an election takes about this.
  private static final long LEADER_POLL_MILLIS = 200;

  private final KafkaConsumer<String, String> consumer;
  private final Set<TopicPartition> partitions;
  private final NotificationService service;
  private final Duration pollTimeout;

  /**
   * Connects to the broker and assigns every partition of the topic.
   *
   * @param pollTimeout how long one poll waits for messages
   */
  public BookingsConsumer(
      String bootstrapServers, String topic, NotificationService service, Duration pollTimeout) {
    Objects.requireNonNull(bootstrapServers, "bootstrapServers");
    Objects.requireNonNull(topic, "topic");
    this.service = Objects.requireNonNull(service, "service");
    this.pollTimeout = Objects.requireNonNull(pollTimeout, "pollTimeout");
    this.consumer =
        new KafkaConsumer<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG,
                NotificationService.CONSUMER_GROUP,
                // Offsets are committed by hand, after the handling: at least once.
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest"),
            new StringDeserializer(),
            new StringDeserializer());
    Set<TopicPartition> found = new HashSet<>();
    consumer
        .partitionsFor(topic)
        .forEach(partition -> found.add(new TopicPartition(topic, partition.partition())));
    this.partitions = Set.copyOf(found);
    consumer.assign(partitions);
  }

  /**
   * Handles every message waiting on the topic, then commits.
   *
   * @return what handling each message did, in the order read
   * @throws IllegalStateException if messages are still waiting when {@code deadline} passes
   */
  public List<NotificationService.Outcome> drain(Duration deadline) {
    Objects.requireNonNull(deadline, "deadline");
    Instant giveUpAt = Instant.now().plus(deadline);
    List<NotificationService.Outcome> outcomes = new ArrayList<>();
    while (waiting() > 0) {
      if (Instant.now().isAfter(giveUpAt)) {
        throw new IllegalStateException(waiting() + " messages still waiting after " + deadline);
      }
      for (ConsumerRecord<String, String> record : consumer.poll(pollTimeout)) {
        outcomes.add(service.handle(record.value()));
      }
      consumer.commitSync();
    }
    return List.copyOf(outcomes);
  }

  /** Messages on the topic that the group has not committed yet: the group's lag. */
  public long waiting() {
    Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
    Map<TopicPartition, Long> beginnings = consumer.beginningOffsets(partitions);
    Map<TopicPartition, OffsetAndMetadata> committed = consumer.committed(partitions);
    long waiting = 0;
    for (TopicPartition partition : partitions) {
      OffsetAndMetadata done = committed.get(partition);
      long from = done == null ? beginnings.get(partition) : done.offset();
      waiting += ends.get(partition) - from;
    }
    return waiting;
  }

  /**
   * Waits until every partition of the topic has a leader, so the first send or read does not race
   * the broker's start; Module 27's topic helper does the same.
   *
   * @throws IllegalStateException if a partition still has no leader at the deadline
   */
  public static void awaitLeaders(String bootstrapServers, String topic, Duration deadline)
      throws InterruptedException {
    Instant giveUpAt = Instant.now().plus(deadline);
    try (Admin admin =
        Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
      while (true) {
        try {
          TopicDescription description =
              admin
                  .describeTopics(List.of(topic))
                  .allTopicNames()
                  .get(deadline.toMillis(), TimeUnit.MILLISECONDS)
                  .get(topic);
          if (description.partitions().stream().allMatch(partition -> partition.leader() != null)) {
            return;
          }
        } catch (ExecutionException | TimeoutException notYet) {
          if (Instant.now().isAfter(giveUpAt)) {
            throw new IllegalStateException("topic " + topic + " has no leaders yet", notYet);
          }
        }
        if (Instant.now().isAfter(giveUpAt)) {
          throw new IllegalStateException("topic " + topic + " still lacks a partition leader");
        }
        TimeUnit.MILLISECONDS.sleep(LEADER_POLL_MILLIS);
      }
    }
  }

  @Override
  public void close() {
    consumer.close();
  }
}
