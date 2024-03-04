package com.ericguerra.collect;

import com.ericguerra.collect.policy.DunningPolicy;
import com.ericguerra.collect.policy.DunningPolicy.Account;
import com.ericguerra.collect.policy.DunningPolicy.Action;
import com.ericguerra.collect.policy.DunningPolicy.Segment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DunningPolicyTest {
    final DunningPolicy policy = new DunningPolicy();
    final LocalDate today = LocalDate.of(2023, 9, 1);

    Account acct(Segment seg, String amount, int daysLate) {
        return new Account("C1", seg, new BigDecimal(amount), today.minusDays(daysLate), false, false, null, 0);
    }

    Action at(Segment seg, String amount, int daysLate) {
        return policy.next(acct(seg, amount, daysLate), today, List.of()).action();
    }

    @Test
    void standardLadder() {
        assertThat(at(Segment.STANDARD, "1000", 1)).isEqualTo(Action.WAIT);
        assertThat(at(Segment.STANDARD, "1000", 5)).isEqualTo(Action.FRIENDLY_REMINDER);
        assertThat(at(Segment.STANDARD, "1000", 20)).isEqualTo(Action.FIRST_NOTICE);
        assertThat(at(Segment.STANDARD, "1000", 35)).isEqualTo(Action.CALL_TASK);
        assertThat(at(Segment.STANDARD, "1000", 50)).isEqualTo(Action.FINAL_NOTICE);
        assertThat(at(Segment.STANDARD, "1000", 70)).isEqualTo(Action.CREDIT_HOLD);
        assertThat(at(Segment.STANDARD, "1000", 100)).isEqualTo(Action.AGENCY_REVIEW);
    }

    @Test
    void segmentsChangeTheLadder() {
        assertThat(at(Segment.STRATEGIC, "50000", 20)).isEqualTo(Action.CALL_TASK);
        assertThat(at(Segment.STRATEGIC, "50000", 120)).isEqualTo(Action.CALL_TASK); // never auto-agency
        assertThat(at(Segment.SMALL, "100", 35)).isEqualTo(Action.FINAL_NOTICE); // not worth a call
    }

    @Test
    void sameStepIsNotRepeated() {
        var step = policy.next(acct(Segment.STANDARD, "1000", 5), today, List.of(Action.FRIENDLY_REMINDER));
        assertThat(step.action()).isEqualTo(Action.WAIT);
        assertThat(step.waitDays()).isEqualTo(10);
    }

    @Test
    void disputesAndPromisesPause() {
        var disputed = new Account("C1", Segment.STANDARD, new BigDecimal("1000"), today.minusDays(40), true, false, null, 0);
        assertThat(policy.next(disputed, today, List.of()).action()).isEqualTo(Action.WAIT);

        var promised = new Account("C1", Segment.STANDARD, new BigDecimal("1000"), today.minusDays(40), false, true, today.plusDays(5), 0);
        var step = policy.next(promised, today, List.of());
        assertThat(step.action()).isEqualTo(Action.WAIT);
        assertThat(step.waitDays()).isEqualTo(8);

        var broken = new Account("C1", Segment.STANDARD, new BigDecimal("1000"), today.minusDays(40), false, true, today.minusDays(5), 1);
        assertThat(policy.next(broken, today, List.of()).action()).isEqualTo(Action.FINAL_NOTICE); // 40 + 7 escalation
    }

    @Test
    void paidStops() {
        assertThat(at(Segment.STANDARD, "0", 70)).isEqualTo(Action.STOP);
    }
}
