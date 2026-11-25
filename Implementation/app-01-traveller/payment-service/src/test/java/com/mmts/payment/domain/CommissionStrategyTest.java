package com.mmts.payment.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The two commission strategies (M4.4), tested on their own. */
class CommissionStrategyTest {

    @Test
    void percentageTakesItsShareInWholePaise() {
        CommissionStrategy fivePercent = new PercentageCommission(500);

        assertThat(fivePercent.commissionFor(4_000)).isEqualTo(200);      // DMRC leg in the contract example
        assertThat(fivePercent.commissionFor(400_000)).isEqualTo(20_000);  // the settlement file example row
        assertThat(fivePercent.commissionFor(4_010)).isEqualTo(201);       // 200.5 rounds up
        assertThat(fivePercent.commissionFor(4_009)).isEqualTo(200);       // 200.45 rounds down
        assertThat(fivePercent.describe()).isEqualTo("PERCENTAGE 5.00%");
    }

    @Test
    void flatFeeIsTheSameForEveryRideButNeverMoreThanTheRide() {
        CommissionStrategy fiveRupees = new FlatFeeCommission(500);

        assertThat(fiveRupees.commissionFor(14_500)).isEqualTo(500);       // ZIPRIDE leg in the contract example
        assertThat(fiveRupees.commissionFor(90_000)).isEqualTo(500);
        assertThat(fiveRupees.commissionFor(300)).isEqualTo(300);          // capped at the ride's price
        assertThat(fiveRupees.describe()).isEqualTo("FLAT_FEE 500 paise");
    }

    @Test
    void invalidRulesAndAmountsAreRefused() {
        assertThatThrownBy(() -> new PercentageCommission(10_001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FlatFeeCommission(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PercentageCommission(500).commissionFor(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
