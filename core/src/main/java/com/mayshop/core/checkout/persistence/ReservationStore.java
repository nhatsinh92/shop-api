package com.mayshop.core.checkout.persistence;

import com.mayshop.core.checkout.Reservation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Storage of reservations in the {@code checkout} schema. */
public interface ReservationStore {

  /**
   * Serialises the callers that change one user's reservations until the transaction ends, so two
   * parallel requests of one user cannot both end up with an active reservation.
   */
  void lockUser(UUID userId);

  /** The user's {@code ACTIVE} reservations with their lines, locked for the transaction. */
  List<Reservation> findActiveForUpdate(UUID userId);

  /**
   * Marks an {@code ACTIVE} reservation {@code RELEASED} and sets {@code released_at}.
   *
   * @return false when it was not active or its stock had been given back already
   */
  boolean markReleased(UUID id, Instant now);

  void insert(Reservation reservation);

  /** The reservation with its lines, only when it belongs to {@code userId}. */
  Optional<Reservation> find(UUID id, UUID userId);

  /** One reservation with its lines, locked for the transaction, whoever owns it. */
  default Optional<Reservation> findForUpdate(UUID id) {
    throw new UnsupportedOperationException();
  }

  /**
   * Up to {@code limit} {@code ACTIVE} reservations with {@code expires_at <= now}, locked for the
   * transaction; rows another transaction has locked are skipped ({@code FOR UPDATE SKIP LOCKED}).
   */
  default List<Reservation> findDueForUpdateSkipLocked(Instant now, int limit) {
    throw new UnsupportedOperationException();
  }

  /**
   * Marks an {@code ACTIVE} reservation {@code EXPIRED} and sets {@code released_at}.
   *
   * @return false when it was not active or its stock had been given back already
   */
  default boolean markExpired(UUID id, Instant now) {
    throw new UnsupportedOperationException();
  }

  /**
   * Marks an {@code ACTIVE} reservation {@code CONSUMED}.
   *
   * @return false when it was not active
   */
  default boolean markConsumed(UUID id) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets {@code restocked_at} of a {@code CONSUMED} reservation, once.
   *
   * @return false when it was not consumed or had been restocked already
   */
  default boolean markRestocked(UUID id, Instant now) {
    throw new UnsupportedOperationException();
  }
}
