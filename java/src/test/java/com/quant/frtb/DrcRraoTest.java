package com.quant.frtb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/** DRC-lite: JTD, issuer netting, HBR; RRAO rates and error paths. */
public class DrcRraoTest {

    private static Sa.DrcPosition make(String issuer, String rating, double notional, double mv) {
        return new Sa.DrcPosition(issuer, rating, notional, mv, 0.75);
    }

    @Test
    public void jtdFormulaLong() {
        // JTD = LGD*N + (MV - N) = 0.75*10M + (9.8M - 10M) = 7.3M
        assertEquals(7.3e6, make("X", "BBB", 10e6, 9.8e6).jtd(), 1e-6);
    }

    @Test
    public void jtdFormulaShort() {
        // short: N = -4M, MV = -3.9M -> -3M + 0.1M = -2.9M
        assertEquals(-2.9e6, make("X", "BBB", -4e6, -3.9e6).jtd(), 1e-6);
    }

    @Test
    public void nettingAndHbrHandCase() {
        // X: 7.3M long + (-2.9M) short -> net +4.4M (BBB, RW 6%)
        // Y: net -0.77M short (B, RW 30%)
        SbmParams params = TestData.params();
        List<Sa.DrcPosition> pos = List.of(
                make("X", "BBB", 10e6, 9.8e6), make("X", "BBB", -4e6, -3.9e6),
                make("Y", "B", -1e6, -1.02e6));
        Sa.DrcResult res = Sa.drcCharge(pos, params);
        double hbr = 4.4e6 / (4.4e6 + 0.77e6);
        assertEquals(4.4e6, res.netJtd().get("X"), 1e-6);
        assertEquals(-0.77e6, res.netJtd().get("Y"), 1e-6);
        assertEquals(hbr, res.hbr(), 1e-12);
        assertEquals(0.06 * 4.4e6 - hbr * 0.30 * 0.77e6, res.charge(), 1e-9);
    }

    @Test
    public void sameIssuerFullNetting() {
        SbmParams params = TestData.params();
        Sa.DrcResult res = Sa.drcCharge(List.of(
                make("X", "BBB", 10e6, 9.8e6), make("X", "BBB", -10e6, -9.8e6)), params);
        assertEquals(0.0, res.netJtd().get("X"), 1e-9);
        assertEquals(0.0, res.charge(), 1e-9);
    }

    @Test
    public void allLongHbrIsOne() {
        SbmParams params = TestData.params();
        Sa.DrcResult res = Sa.drcCharge(List.of(
                make("X", "BBB", 10e6, 10e6), make("Y", "BB", 5e6, 5e6)), params);
        assertEquals(1.0, res.hbr(), 0.0);
        assertEquals(0.06 * 7.5e6 + 0.15 * 3.75e6, res.charge(), 1e-9);
    }

    @Test
    public void shortDominatedFloorAtZero() {
        SbmParams params = TestData.params();
        assertEquals(0.0, Sa.drcCharge(List.of(
                make("X", "AAA", 1e6, 1e6), make("Y", "CCC", -10e6, -10e6)), params).charge(),
                0.0);
    }

    @Test
    public void emptyBook() {
        Sa.DrcResult res = Sa.drcCharge(List.of(), TestData.params());
        assertEquals(0.0, res.charge(), 0.0);
        assertEquals(1.0, res.hbr(), 0.0);
    }

    @Test
    public void unknownRatingThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Sa.drcCharge(List.of(make("X", "ZZZ", 1e6, 1e6)), TestData.params()));
        assertTrue(e.getMessage().contains("DRC risk weight"));
    }

    @Test
    public void inconsistentIssuerRatingThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Sa.drcCharge(List.of(
                        make("X", "BBB", 1e6, 1e6), make("X", "BB", 1e6, 1e6)),
                        TestData.params()));
        assertTrue(e.getMessage().contains("inconsistent"));
    }

    @Test
    public void rraoRates() {
        SbmParams params = TestData.params();
        Bond bond = new Bond("b", 1e6, 0.05, 2.0, "USD", "I", "BBB", 0.75,
                new RraoFlag("other", 1e6));
        Bond exotic = new Bond("e", 2e6, 0.05, 2.0, "USD", "I", "BBB", 0.75,
                new RraoFlag("exotic", 2e6));
        Bond plain = new Bond("p", 3e6, 0.05, 2.0, "USD", "I", "BBB", 0.75, null);
        // 0.1% * 1M + 1.0% * 2M = 1_000 + 20_000
        assertEquals(21000.0, Sa.rraoCharge(List.of(bond, exotic, plain), params), 1e-12);
    }

    @Test
    public void rraoEmpty() {
        assertEquals(0.0, Sa.rraoCharge(List.of(), TestData.params()), 0.0);
    }

    @Test
    public void badRraoCategoryThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new RraoFlag("weird", 1e6));
        assertTrue(e.getMessage().contains("exotic"));
    }
}
