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
 * and, for COD, stores the 201 response. An online order is created {@code PENDING_PAYMENT}; after
 * the commit the gateway is charged and transaction 2 sets {@code PAID} or {@code PAYMENT_FAILED}
 * (giving the stock back) and stores the 201 response. A domain error rolls it back, so the key
 * leaves no row. When the reservation had expired, its stock is released in its own transaction
 * after the rollback: {@link Reservations#consume} holds the row lock, so calling {@link
 * Reservations#expire} inside would wait for itself.
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
    Step step;
    try {
      // Resolved per call: contexts without a database have no transaction manager.
      step =
          new TransactionTemplate(transactions.getObject())
              .execute(status -> placeInTransaction(request, now));
    } catch (OrderProblemException e) {
      if (e.status() == 410 && "CHECKOUT_RESERVATION_EXPIRED".equals(e.code())) {
        releaseExpired(request.reservationId(), now);
      }
      throw e;
    }
    return switch (step) {
      case Step.Done done -> done.placed();
      case Step.Charge charge -> settle(request, charge);
    };
  }

  /** What transaction 1 decided: the answer is ready, or the order still has to be charged. */
  private sealed interface Step {
    record Done(Placed placed) implements Step {}

    record Charge(UUID orderId, UUID reservationId, long total) implements Step {}
  }

  /**
   * Charges the gateway outside any transaction, then runs transaction 2. If this process dies
   * between the two, the order stays {@code PENDING_PAYMENT} and {@link OrderRecovery} settles it.
   */
  private Placed settle(PlaceOrder request, Step.Charge charge) {
    Payments.Outcome outcome =
        payments.charge(
            charge.orderId(), charge.total(), request.paymentMethod(), request.mockOutcome());
    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    return new TransactionTemplate(transactions.getObject())
        .execute(status -> finishPayment(request, charge, outcome, now));
  }

  private Placed finishPayment(
      PlaceOrder request, Step.Charge charge, Payments.Outcome outcome, Instant now) {
    var locked = orders.lock(charge.orderId()).orElseThrow();
    if (!"PENDING_PAYMENT".equals(locked.status())) {
      // The recovery job settled it first and completed the key: answer with that result.
      return new Placed(201, JSON.writeValueAsString(view(charge.orderId(), request)), false);
    }
    String status = outcome == Payments.Outcome.SUCCEEDED ? "PAID" : "PAYMENT_FAILED";
    orders.updateStatus(charge.orderId(), status, outcome.name(), now);
    if (outcome != Payments.Outcome.SUCCEEDED) {
      reservations.restock(charge.reservationId());
    }
    String body = JSON.writeValueAsString(view(charge.orderId(), request));
    idempotency.complete(request.userId(), request.key(), charge.orderId(), 201, body);
    return new Placed(201, body, false);
  }

  private OrderView view(UUID orderId, PlaceOrder request) {
    return orders.find(orderId, request.userId()).orElseThrow();
  }

  /** The caller's order, or {@link OrderProblemException#orderNotFound()}. */
  public OrderView get(UUID id, UUID userId) {
    return orders.find(id, userId).orElseThrow(OrderProblemException::orderNotFound);
  }

  private Step placeInTransaction(PlaceOrder request, Instant now) {
    BeginResult begun = idempotency.begin(request.userId(), request.key(), request.fingerprint());
    switch (begun) {
      case BeginResult.Replay replay -> {
        return new Step.Done(new Placed(replay.status(), replay.body(), true));
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
    boolean online = request.paymentMethod() != Payments.Method.COD;
    Optional<Long> fee = shipping.fee(request.shippingOptionId());
    long shippingFee = fee.orElseThrow(() -> new IllegalStateException("Unknown shipping option"));

    UUID orderId = UUID.randomUUID();
    NewOrder order =
        new NewOrder(
            orderId,
            numbers.next(),
            request.userId(),
            request.reservationId(),
            online ? "PENDING_PAYMENT" : "PLACED",
            subtotal,
            request.shippingOptionId(),
            shippingFee,
            request.paymentMethod().name(),
            online ? "PENDING" : "NOT_REQUIRED",
            request.shippingAddressJson(),
            request.note(),
            now);
    List<NewItem> items =
        lines.stream().map(l -> new NewItem(l.sku(), l.quantity(), l.unitPrice())).toList();
    orders.insert(order, items);
    if (online) {
      idempotency.link(request.userId(), request.key(), orderId);
      return new Step.Charge(orderId, request.reservationId(), order.total());
    }

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
    return new Step.Done(new Placed(201, body, false));
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
