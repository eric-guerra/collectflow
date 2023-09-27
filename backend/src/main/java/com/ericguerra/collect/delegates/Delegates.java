package com.ericguerra.collect.delegates;

import com.ericguerra.collect.policy.DunningPolicy;
import com.ericguerra.collect.policy.DunningPolicy.Action;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Process variables are kept small (ids, the next action); account data is read fresh from AR each loop. */
public final class Delegates {
    private Delegates() {}

    @Component("decideNextStep")
    public static class DecideNextStep implements JavaDelegate {
        private final JdbcTemplate jdbc;
        private final Clock clock;
        private final DunningPolicy policy = new DunningPolicy();

        public DecideNextStep(JdbcTemplate jdbc, Clock clock) {
            this.jdbc = jdbc;
            this.clock = clock;
        }

        @Override
        public void execute(DelegateExecution ex) {
            String customer = (String) ex.getVariable("customerId");
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT segment, overdue_amount, oldest_due, disputed, promise_date, broken_promises
                    FROM ar_accounts WHERE customer_id = ?""", customer);
            LocalDate promise = row.get("promise_date") == null ? null : ((java.sql.Date) row.get("promise_date")).toLocalDate();
            var account = new DunningPolicy.Account(customer,
                    DunningPolicy.Segment.valueOf((String) row.get("segment")),
                    (BigDecimal) row.get("overdue_amount"),
                    ((java.sql.Date) row.get("oldest_due")).toLocalDate(),
                    (Boolean) row.get("disputed"),
                    promise != null, promise,
                    ((Number) row.get("broken_promises")).intValue());
            @SuppressWarnings("unchecked")
            List<String> done = (List<String>) ex.getVariable("actionsDone");
            if (done == null) done = new ArrayList<>();
            var step = policy.next(account, LocalDate.now(clock), done.stream().map(Action::valueOf).toList());
            ex.setVariable("nextAction", step.action().name());
            ex.setVariable("reason", step.reason());
            ex.setVariable("waitDuration", "P" + Math.max(1, step.waitDays()) + "D"); // ISO-8601 for the timer event
            // Denormalized for the collector worklist (avoids a second lookup per task in the UI).
            ex.setVariable("overdueAmount", account.overdueAmount().doubleValue());
            ex.setVariable("daysLate", java.time.temporal.ChronoUnit.DAYS.between(account.oldestDue(), LocalDate.now(clock)));
            ex.setVariable("segment", account.segment().name());
        }
    }

    /** Sends a templated email/letter; records the action so the policy won't repeat it. */
    @Component("sendNotice")
    public static class SendNotice implements JavaDelegate {
        private final JdbcTemplate jdbc;

        public SendNotice(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public void execute(DelegateExecution ex) {
            String customer = (String) ex.getVariable("customerId");
            String action = (String) ex.getVariable("nextAction");
            String email = jdbc.queryForObject("SELECT billing_email FROM ar_accounts WHERE customer_id = ?", String.class, customer);
            if (email == null || email.isBlank()) throw new BpmnError("NO_CONTACT", "no billing email for " + customer);
            jdbc.update("INSERT INTO dunning_log (customer_id, action, channel, sent_to, process_instance) VALUES (?, ?, 'email', ?, ?)",
                    customer, action, email, ex.getProcessInstanceId());
            markDone(ex, action);
        }
    }

    @Component("applyCreditHold")
    public static class ApplyCreditHold implements JavaDelegate {
        private final JdbcTemplate jdbc;

        public ApplyCreditHold(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public void execute(DelegateExecution ex) {
            jdbc.update("UPDATE ar_accounts SET credit_hold = TRUE WHERE customer_id = ?", ex.getVariable("customerId"));
            markDone(ex, "CREDIT_HOLD");
        }
    }

    /** Collector logged the call outcome on the user task form; record a promise-to-pay if one was given. */
    @Component("recordCallOutcome")
    public static class RecordCallOutcome implements JavaDelegate {
        private final JdbcTemplate jdbc;

        public RecordCallOutcome(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public void execute(DelegateExecution ex) {
            String outcome = (String) ex.getVariable("callOutcome");
            if ("PROMISE_TO_PAY".equals(outcome)) {
                jdbc.update("UPDATE ar_accounts SET promise_date = ? WHERE customer_id = ?",
                        java.sql.Date.valueOf((String) ex.getVariable("promiseDate")), ex.getVariable("customerId"));
            } else if ("DISPUTE".equals(outcome)) {
                jdbc.update("UPDATE ar_accounts SET disputed = TRUE WHERE customer_id = ?", ex.getVariable("customerId"));
            }
            markDone(ex, ex.getVariable("nextAction").toString());
        }
    }

    static void markDone(DelegateExecution ex, String action) {
        @SuppressWarnings("unchecked")
        List<String> done = (List<String>) ex.getVariable("actionsDone");
        List<String> next = done == null ? new ArrayList<>() : new ArrayList<>(done);
        next.add(action);
        ex.setVariable("actionsDone", next);
    }
}
