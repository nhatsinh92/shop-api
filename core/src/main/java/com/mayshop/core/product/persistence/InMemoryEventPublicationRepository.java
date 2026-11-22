package com.mayshop.core.product.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;

/**
 * Event publication registry for contexts without a database (see {@link
 * EventRegistryConfiguration}): keeps incomplete publications in a map and forgets completed ones.
 */
class InMemoryEventPublicationRepository implements EventPublicationRepository {

  private final Map<UUID, TargetEventPublication> incomplete = new ConcurrentHashMap<>();

  @Override
  public TargetEventPublication create(TargetEventPublication publication) {
    incomplete.put(publication.getIdentifier(), publication);
    return publication;
  }

  @Override
  public void markCompleted(
      Object event, PublicationTargetIdentifier identifier, Instant completionDate) {
    incomplete.values().removeIf(p -> p.isAssociatedWith(event, identifier));
  }

  @Override
  public void markCompleted(UUID identifier, Instant completionDate) {
    incomplete.remove(identifier);
  }

  @Override
  public List<TargetEventPublication> findIncompletePublications() {
    return new ArrayList<>(incomplete.values());
  }

  @Override
  public List<TargetEventPublication> findIncompletePublicationsPublishedBefore(Instant instant) {
    return incomplete.values().stream()
        .filter(p -> p.getPublicationDate().isBefore(instant))
        .toList();
  }

  @Override
  public Optional<TargetEventPublication> findIncompletePublicationsByEventAndTargetIdentifier(
      Object event, PublicationTargetIdentifier identifier) {
    return incomplete.values().stream()
        .filter(p -> p.isAssociatedWith(event, identifier))
        .findFirst();
  }

  @Override
  public void deletePublications(List<UUID> identifiers) {
    identifiers.forEach(incomplete::remove);
  }

  @Override
  public void deleteCompletedPublications() {
    // Completed publications are not kept.
  }

  @Override
  public void deleteCompletedPublicationsBefore(Instant instant) {
    // Completed publications are not kept.
  }
}
