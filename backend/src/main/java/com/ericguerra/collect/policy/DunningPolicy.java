package com.ericguerra.collect.policy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Decides the next collections step for an overdue account. Kept as plain Java (not BPMN gateways)
 * so finance can review the rules in one place and they're unit-tested; the process calls it.
 */
public final class DunningPolicy {
    public enum Segment { STRATEGIC, STANDARD, SMALL }
    public enum Action { WAIT, FRIENDLY_REMINDER, FIRST_NOTICE, CALL_TASK, FINAL_NOTICE, CREDIT_HOLD, AGENCY_REVIEW, STOP }

    public record Account(String customerId, Segment segment, BigDecimal overdueAmount, LocalDate oldestDue,
                          boolean disputed, boolean promiseToPayActive, LocalDate promiseDate, int brokenPromises) {}

    public record Step(Action action, String reason, int waitDays) {}

    /** Strategic accounts are never sent to an agency automatically and get a human call earlier. */
    public Step next(Account a, LocalDate today, List<Action> alreadyDone) {
        if (a.overdueAmount().signum() <= 0) return new Step(Action.STOP, "paid in full", 0);
        if (a.disputed()) return new Step(Action.WAIT, "dispute open; dunning paused", 7);
        if (a.promiseToPayActive()) {
            if (!today.isAfter(a.promiseDate().plusDays(2))) return new Step(Action.WAIT, "promise to pay until " + a.promiseDate(), (int) ChronoUnit.DAYS.between(today, a.promiseDate().plusDays(3)));
            // broken promise: escalate one level faster
        }
        long late = ChronoUnit.DAYS.between(a.oldestDue(), today);
        int escalation = a.brokenPromises() * 7; // each broken promise pulls the schedule forward a week
        long eff = late + escalation;

        boolean small = a.overdueAmount().compareTo(new BigDecimal("250")) < 0;
        if (eff < 3) return new Step(Action.WAIT, "within grace period", (int) (3 - eff));
        if (eff < 15) return once(Action.FRIENDLY_REMINDER, alreadyDone, "3+ days late", 15 - eff);
        if (eff < 30) return a.segment() == Segment.STRATEGIC
                ? once(Action.CALL_TASK, alreadyDone, "strategic account 15+ days late: account manager calls", 30 - eff)
                : once(Action.FIRST_NOTICE, alreadyDone, "15+ days late", 30 - eff);
        if (eff < 45) return small ? once(Action.FINAL_NOTICE, alreadyDone, "small balance, skip call", 45 - eff)
                : once(Action.CALL_TASK, alreadyDone, "30+ days late: collector call", 45 - eff);
        if (eff < 60) return once(Action.FINAL_NOTICE, alreadyDone, "45+ days late", 60 - eff);
        if (eff < 90) return once(Action.CREDIT_HOLD, alreadyDone, "60+ days late: block new orders", 90 - eff);
        if (a.segment() == Segment.STRATEGIC) return once(Action.CALL_TASK, alreadyDone, "90+ days, strategic: escalate to finance director", 14);
        return once(Action.AGENCY_REVIEW, alreadyDone, "90+ days late: review for collections agency", 30);
    }

    private static Step once(Action a, List<Action> done, String reason, long waitIfDone) {
        // Each communication goes out once per stage; repeats wait until the next stage.
        if (done.contains(a)) return new Step(Action.WAIT, a + " already done", (int) Math.max(1, waitIfDone));
        return new Step(a, reason, 0);
    }
}
