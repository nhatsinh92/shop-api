package com.mayshop.core.checkout;

import com.mayshop.core.checkout.Reservation.Status;
import com.mayshop.core.checkout.persistence.ReservationStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A {@link ReservationStore} without a database. It has no transactions: nothing rolls back. */
class InMemoryReservationStore implements ReservationStore {

  private final Map<UUID, Reservation> rows = new LinkedHashMap<>();
  private final Map<UUID, Instant> releasedAt = new LinkedHashMap<>();

  void clear() {
    rows.clear();
    releasedAt.clear();
  }

  Reservation get(UUID id) {
    return rows.get(id);
  }

  Instant releasedAt(UUID id) {
    return releasedAt.get(id);
  }

  List<Reservation> all() {
    return new ArrayList<>(rows.values());
  }

  @Override
  public void lockUser(UUID userId) {}

  @Override
  public List<Reservation> findActiveForUpdate(UUID userId) {
    return rows.values().stream()
        .filter(r -> r.userId().equals(userId) && r.status() == Status.ACTIVE)
        .toList();
  }

  @Override
  public boolean markReleased(UUID id, Instant now) {
    Reservation r = rows.get(id);
    if (r == null || r.status() != Status.ACTIVE || releasedAt.containsKey(id)) {
      return false;
    }
    rows.put(
        id,
        new Reservation(
            r.id(), r.userId(), Status.RELEASED, r.createdAt(), r.expiresAt(), r.items()));
    releasedAt.put(id, now);
    return true;
  }

  @Override
  public void insert(Reservation reservation) {
    rows.put(reservation.id(), reservation);
  }

  @Override
  public Optional<Reservation> find(UUID id, UUID userId) {
    return Optional.ofNullable(rows.get(id)).filter(r -> r.userId().equals(userId));
  }
}
