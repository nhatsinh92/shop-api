package com.mayshop.core.order.internal;

import com.mayshop.core.checkout.Payments;
import com.mayshop.core.checkout.Reservations;
import com.mayshop.core.checkout.Reservations.ConsumeResult;
import com.mayshop.core.checkout.Reservations.ConsumedLine;
import com.mayshop.core.checkout.ShippingOptions;
import com.mayshop.core.order.idempotency.BeginResult;
import com.mayshop.core.order.idempotency.IdempotencyStore;
import com.mayshop.core.order.number.OrderNumberGenerator;
import com.mayshop.core.order.persistence.OrderStore;
import com.mayshop.core.order.persistence.OrderStore.NewItem;
import com.mayshop.core.order.persistence.OrderStore.NewOrder;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Places an order from a reservation exactly once per idempotency key.
 *
 * <p>Transaction 1 inserts the {@code IN_PROGRESS} key, consumes the reservation, creates the order
 * and, for COD, stores the 201 response. A domain error rolls it back, so the key leaves no row.
 * When the reservation had expired, its stock is released in its own transaction after the
 * rollback: {@link Reservations#consume} holds the row lock, so calling {@link Reservations#expire}
 * inside would wait for itself.
 */
@Service
public class OrderPlacement {

  private static final Logger LOG = LoggerFactory.getLogger(OrderPlacement.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * The answer to send.
   *
   * @param body the JSON body, exactly as stored for replays
   * @param replayed true when it is the stored answer of an earlier request
   */
  public record Placed(int status, String body, boolean replayed) {}

  private final Reservations reservations;
  private final ShippingOptions shipping;
  private final Payments payments;
  private final IdempotencyStore idempotency;
  private final OrderNumberGenerator numbers;
  private final OrderStore orders;
  private final ObjectProvider<PlatformTransactionManager> transactions;
  private final Clock clock;

  OrderPlacement(
      Reservations reservations,
      ShippingOptions shipping,
      Payments payments,
      IdempotencyStore idempotency,
      OrderNumberGenerator numbers,
      OrderStore orders,
      ObjectProvider<PlatformTransactionManager> transactions,
      Clock clock) {
    this.reservations = reservations;
    this.shipping = shipping;
    this.payments = payments;
    this.idempotency = idempotency;
    this.numbers = numbers;
    this.orders = orders;
    this.transactions = transactions;
    this.clock = clock;
  }

  /**
   * Runs the request.
   *
   * @throws OrderProblemException for every documented 4xx domain error
   */
  public Placed place(PlaceOrder request) {
    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    try {
      // Resolved per call: contexts without a database have no transaction manager.
      return new TransactionTemplate(transactions.getObject())
          .execute(status -> placeInTransaction(request, now));
    } catch (OrderProblemException e) {
      if (e.status() == 410 && "CHECKOUT_RESERVATION_EXPIRED".equals(e.code())) {
        releaseExpired(request.reservationId(), now);
      }
      throw e;
    }
  }

  /** The caller's order, or {@link OrderProblemException#orderNotFound()}. */
  public OrderView get(UUID id, UUID userId) {
    return orders.find(id, userId).orElseThrow(OrderProblemException::orderNotFound);
  }

  private Placed placeInTransaction(PlaceOrder request, Instant now) {
    BeginResult begun = idempotency.begin(request.userId(), request.key(), request.fingerprint());
    switch (begun) {
      case BeginResult.Replay replay -> {
        return new Placed(replay.status(), replay.body(), true);
      }
      case BeginResult.InProgress inProgress -> throw OrderProblemException.inProgress();
      case BeginResult.Reused reused -> throw OrderProblemException.keyReused();
      case BeginResult.Started started -> {
        // continue below
      }
    }

    ConsumeResult consumed = reservations.consume(request.reservationId(), request.userId(), now);
    List<ConsumedLine> lines;
    long subtotal;
    switch (consumed) {
      case ConsumeResult.NotFound notFound -> throw OrderProblemException.notFound();
      case ConsumeResult.Expired expired -> throw OrderProblemException.expired();
      case ConsumeResult.AlreadyConsumed already -> throw OrderProblemException.consumed();
      case ConsumeResult.Consumed ok -> {
        lines = ok.lines();
        subtotal = ok.subtotal();
      }
    }

    if (!payments.isAvailable(request.paymentMethod())) {
      throw OrderProblemException.paymentMethodUnavailable();
    }
    if (request.paymentMethod() != Payments.Method.COD) {
      // Online payment arrives with the payment task; until then it is reported as unavailable.
      throw OrderProblemException.paymentMethodUnavailable();
    }
    Optional<Long> fee = shipping.fee(request.shippingOptionId());
    long shippingFee = fee.orElseThrow(() -> new IllegalStateException("Unknown shipping option"));

    UUID orderId = UUID.randomUUID();
    NewOrder order =
        new NewOrder(
            orderId,
            numbers.next(),
            request.userId(),
            request.reservationId(),
            "PLACED",
            subtotal,
            request.shippingOptionId(),
            shippingFee,
            request.paymentMethod().name(),
            "NOT_REQUIRED",
            request.shippingAddressJson(),
            request.note(),
            now);
    List<NewItem> items =
        lines.stream().map(l -> new NewItem(l.sku(), l.quantity(), l.unitPrice())).toList();
    orders.insert(order, items);

    OrderView view =
        new OrderView(
            orderId,
            order.number(),
            order.status(),
            items.stream()
                .map(i ->
                    new OrderView.Item(
                        i.sku(),
                        i.quantity(),
                        OrderView.Money.vnd(i.unitPrice()),
                        OrderView.Money.vnd(i.unitPrice() * i.quantity())))
                .toList(),
            OrderView.Money.vnd(subtotal),
            OrderView.Money.vnd(shippingFee),
            OrderView.Money.vnd(order.total()),
            new OrderView.Payment(order.paymentMethod(), order.paymentStatus()),
            now.toString());
    String body = JSON.writeValueAsString(view);
    idempotency.complete(request.userId(), request.key(), orderId, 201, body);
    return new Placed(201, body, false);
  }

  private void releaseExpired(UUID reservationId, Instant now) {
    try {
      reservations.expire(reservationId, now);
    } catch (RuntimeException e) {
      // The 410 still goes out; the sweep gives the stock back later.
      LOG.warn("Could not release the stock of expired reservation {}", reservationId, e);
    }
  }
}
