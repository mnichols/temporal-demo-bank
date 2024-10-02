package io.temporal.finance.domain.orchestrations;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.finance.domain.merchants.MerchantsHandlers;
import io.temporal.finance.domain.messaging.commands.*;
import io.temporal.finance.domain.messaging.orchestrations.Errors;
import io.temporal.finance.domain.messaging.orchestrations.MakePaymentRequest;
import io.temporal.finance.domain.messaging.queries.PaymentState;
import io.temporal.finance.domain.messaging.values.PaymentDescriptor;
import io.temporal.finance.domain.payments.PaymentsHandlers;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class MakePaymentImpl implements MakePayment {
  private final PaymentsHandlers payments;
  private final MerchantsHandlers merchants;
  private PaymentState state;

  public MakePaymentImpl() {
    this.payments =
        Workflow.newActivityStub(
            PaymentsHandlers.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setBackoffCoefficient(2)
                        .setInitialInterval(Duration.ofSeconds(3))
                        .setMaximumInterval(Duration.ofSeconds(10))
                        // we can handle this special case failure!
                        .setDoNotRetry(Errors.PAYMENT_GATEWAY_UNREACHABLE.name())
                        .build())
                .build());
    this.merchants =
        Workflow.newActivityStub(
            MerchantsHandlers.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
                .build());
  }

  @Override
  public void execute(MakePaymentRequest params) {
    // first lets initialize the state of our payment (entity pattern)
    this.state = new PaymentState(params);
    // a recursive call to the payment can set up some nice ways to handle
    // custom logic for outages and so on
    if (state.getRequestAttempts() > 5) {
      throw ApplicationFailure.newFailure(
          "Exceeded amount of attempts for this payment. \nI COULD send a notification or call some activity fer now we will just fail.",
          Errors.INVALID_PAYMENT.name());
    }

    if (params.requestAttempts() == 0) {
      // right now, we are just failing if any validation errors or returns !ok
      // however we could just as easily alter the charge amount here and await for an alternative
      // payment for the balance
      var validationErrors = this.validatePaymentMethods(this.state);
      if (!validationErrors.isEmpty() || !state.isValid()) {
        throw ApplicationFailure.newFailure("Validation failed.", Errors.INVALID_PAYMENT.name());
      }
    }

    // this is safe to do without breaking determinism!
    this.state.setTotalAmountCents(
        Arrays.stream(params.descriptors())
            .map(PaymentDescriptor::amountCents)
            .reduce(0, Integer::sum));
    // SAGA!
    try {
      // use our payment gateway to create a transaction then mutate our own application with the
      // result
      // compensate with a reversal of charges if we cant write down the transaction
      var trx =
          payments.createTransaction(
              new CreateTransactionRequest(
                  params.merchantId(), params.remoteId(), this.state.getTotalAmountCents()));
      this.state.setTransaction(trx);
      this.merchants.recordTransaction(
          new RecordTransactionRequest(
              this.state.getMerchantId(),
              this.state.getRemoteId(),
              this.state.getTransaction().transactionId(),
              this.state.getTotalAmountCents()));

    } catch (ActivityFailure e) {
      if (e.getCause() instanceof ApplicationFailure af) {
        if (Objects.equals(af.getType(), Errors.PAYMENT_GATEWAY_UNREACHABLE.name())) {
          this.handleUnreachablePaymentGateway(af, state);
          return;
        }
        // compensate in the event of this failure
        if (this.state.getTransaction() != null) {
          payments.reverseTransaction(
              new ReverseTransactionRequest(
                  this.state.getTransaction().transactionId(), af.getMessage()));
        }
        throw e;
      }
    }
  }

  private void handleUnreachablePaymentGateway(
      ApplicationFailure originalError, PaymentState state) {

    var can = Workflow.newContinueAsNewStub(MakePayment.class);
    can.execute(
        new MakePaymentRequest(
            state.getRemoteId(),
            state.getMerchantId(),
            state.getDescriptors().toArray(new PaymentDescriptor[0]),
            state.tipAmountCents(),
            state.getRequestAttempts() + 1));
  }

  private List<RuntimeException> validatePaymentMethods(PaymentState state) {
    var validations = new ArrayList<Promise<ValidatePaymentMethodResponse>>();
    var errors = new ArrayList<RuntimeException>();
    for (PaymentDescriptor desc : state.getDescriptors()) {
      var cmd = new ValidatePaymentMethodRequest(state.getMerchantId(), state.getRemoteId(), desc);
      validations.add(
          Async.function(payments::validatePaymentType, cmd)
              .handle(
                  (value, error) -> {
                    if (error != null) {
                      errors.add(error);
                    }
                    if (!value.isOk()) {
                      state.setValid(false);
                    }
                    return value;
                  }));
    }
    Promise.allOf(validations).get();
    return errors;
  }

  @Override
  public PaymentState getState() {
    return this.state;
  }
}
