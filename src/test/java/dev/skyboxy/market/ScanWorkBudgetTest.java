package dev.skyboxy.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ScanWorkBudgetTest {
    @Test
    void shouldNeverConsumeMoreComparisonsThanTheTickLimit() {
        ScanWorkBudget budget = new ScanWorkBudget(2);

        assertTrue(budget.tryConsume());
        assertTrue(budget.tryConsume());
        assertFalse(budget.tryConsume());
        assertEquals(2, budget.used());
        assertEquals(0, budget.remaining());
    }
}
