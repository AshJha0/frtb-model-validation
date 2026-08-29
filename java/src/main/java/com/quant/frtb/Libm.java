package com.quant.frtb;

/**
 * Bit-exact ports of the elementary functions {@code exp}, {@code log} and
 * {@code erf} from glibc 2.39 (the x86-64 FMA dispatch path that the Python
 * reference implementation calls through libm).
 *
 * <p><b>Why:</b> the cross-language golden values in
 * {@code data/golden/golden.json} carry absolute tolerances as tight as 1e-8
 * on capital numbers of order 1e6 that are produced by bump-and-revalue
 * finite differences. A single 1-ulp deviation of {@code exp} inside a
 * discount factor can move a weighted sensitivity by ~1e-7, so
 * {@link Math#exp(double)} (which disagrees with glibc on ~0.3% of inputs by
 * 1 ulp) is not reproducible enough. These ports follow the glibc algorithms
 * and data tables exactly, including the FMA contractions that GCC applies
 * when compiling the {@code __ieee754_exp_fma} / {@code __ieee754_log_fma}
 * variants ({@code Math#fma} maps to the same fused hardware operation).
 *
 * <p>The {@code erf} port is glibc's {@code sysdeps/ieee754/dbl-64/s_erf.c}
 * (fdlibm-derived), which x86-64 compiles without FMA; it calls the exp port
 * internally, exactly as glibc's erf calls {@code __ieee754_exp}.
 *
 * <p>All functions are pure and deterministic; special cases (NaN, infinity,
 * overflow/underflow) follow IEEE semantics rather than raising errors.
 */
public final class Libm {

    private Libm() {
    }

    // ================================================================ exp ==

    private static final int EXP_TABLE_BITS = 7;
    private static final int EXP_N = 1 << EXP_TABLE_BITS;

    /**
     * e^x, bit-identical to glibc 2.39 {@code __ieee754_exp_fma} for all
     * finite arguments (returns +infinity on overflow, 0 on underflow).
     *
     * @param x exponent
     * @return e^x rounded to nearest double exactly as glibc rounds it
     */
    public static double exp(double x) {
        long ux = Double.doubleToRawLongBits(x);
        int abstop = (int) ((ux >>> 52) & 0x7ff);
        // top12(0x1p-54) = 0x3c9, top12(512.0) = 0x408, top12(1024.0) = 0x409
        if (abstop - 0x3c9 >= 0x408 - 0x3c9 || abstop < 0x3c9) {
            if (abstop < 0x3c9) {
                return 1.0 + x; // |x| < 2^-54 (0 is a common input)
            }
            if (abstop >= 0x409) {
                if (ux == 0xfff0000000000000L) {
                    return 0.0; // exp(-inf)
                }
                if (abstop >= 0x7ff) {
                    return 1.0 + x; // NaN / +inf
                }
                return (ux >>> 63) != 0 ? 0.0 : Double.POSITIVE_INFINITY;
            }
            abstop = 0; // |x| >= 512: special-cased scaling below
        }

        // exp(x) = 2^(k/N) * exp(r), x = ln2/N*k + r, |r| <= ln2/2N.
        // GCC contracts z = InvLn2N*x; kd = z + Shift into one fma.
        double kd = Math.fma(EXP_INVLN2N, x, EXP_SHIFT);
        long ki = Double.doubleToRawLongBits(kd);
        kd -= EXP_SHIFT;
        double r = Math.fma(kd, EXP_NEGLN2HIN, x);
        r = Math.fma(kd, EXP_NEGLN2LON, r);
        // 2^(k/N) ~= scale * (1 + tail)
        int idx = 2 * (int) (ki & (EXP_N - 1));
        long top = ki << (52 - EXP_TABLE_BITS);
        double tail = Double.longBitsToDouble(EXP_TAB[idx]);
        long sbits = EXP_TAB[idx + 1] + top;
        double r2 = r * r;
        // tmp = tail + r + r2*(C2 + r*C3) + r2*r2*(C4 + r*C5), fma-contracted
        double tmp = Math.fma(r2 * r2, Math.fma(r, EXP_C5, EXP_C4),
                Math.fma(r2, Math.fma(r, EXP_C3, EXP_C2), tail + r));
        if (abstop == 0) {
            return expSpecialCase(tmp, sbits, ki);
        }
        double scale = Double.longBitsToDouble(sbits);
        return Math.fma(scale, tmp, scale);
    }

    /** Overflow/underflow-region scaling, ported from glibc's specialcase(). */
    private static double expSpecialCase(double tmp, long sbits, long ki) {
        if ((ki & 0x80000000L) == 0) {
            // k > 0: the exponent of scale might have overflowed by <= 460
            sbits -= 1009L << 52;
            double scale = Double.longBitsToDouble(sbits);
            return 0x1p1009 * Math.fma(scale, tmp, scale);
        }
        // k < 0: subnormal range needs careful rounding
        sbits += 1022L << 52;
        double scale = Double.longBitsToDouble(sbits);
        double y = scale + scale * tmp;
        if (y < 1.0) {
            double lo = scale - y + scale * tmp;
            double hi = 1.0 + y;
            lo = 1.0 - hi + y + lo;
            y = (hi + lo) - 1.0;
            if (y == 0.0) {
                y = 0.0; // avoid -0.0 with downward rounding
            }
        }
        return 0x1p-1022 * y;
    }

    // ================================================================ log ==

    private static final int LOG_TABLE_BITS = 7;
    private static final int LOG_N = 1 << LOG_TABLE_BITS;
    private static final long LOG_OFF = 0x3fe6000000000000L;
    private static final long LOG_LO = Double.doubleToRawLongBits(1.0 - 0x1p-4);
    private static final long LOG_HI = Double.doubleToRawLongBits(1.0 + 0x1.09p-4);

    /**
     * Natural logarithm, bit-identical to glibc 2.39 {@code __ieee754_log_fma}
     * for positive finite arguments (returns -infinity at 0, NaN for x &lt; 0).
     *
     * @param x argument
     * @return ln(x) rounded to nearest double exactly as glibc rounds it
     */
    public static double log(double x) {
        long ix = Double.doubleToRawLongBits(x);
        int top = (int) (ix >>> 48);

        if (Long.compareUnsigned(ix - LOG_LO, LOG_HI - LOG_LO) < 0) {
            // inputs close to 1.0 handled separately
            if (ix == 0x3ff0000000000000L) {
                return 0.0;
            }
            double r = x - 1.0;
            double r2 = r * r;
            double r3 = r * r2;
            // y-polynomial with GCC's fma contractions
            double q1 = Math.fma(r2, LOG_B3, Math.fma(r, LOG_B2, LOG_B1));
            double q2 = Math.fma(r2, LOG_B6, Math.fma(r, LOG_B5, LOG_B4));
            double q3 = Math.fma(r3, LOG_B10,
                    Math.fma(r2, LOG_B9, Math.fma(r, LOG_B8, LOG_B7)));
            double yq = Math.fma(Math.fma(q3, r3, q2), r3, q1);
            // hi/lo split of r (w = r*2^27; rhi = r + w - w, contracted)
            double t = Math.fma(r, 0x1p27, r);
            double rhi = Math.fma(-0x1p27, r, t);
            double rlo = r - rhi;
            double rhi2 = rhi * rhi;
            double hi = Math.fma(rhi2, LOG_B0, r); // B0 = -0.5
            double lo = Math.fma(rhi2, LOG_B0, r - hi);
            lo = Math.fma(LOG_B0 * rlo, rhi + r, lo);
            return hi + Math.fma(yq, r3, lo);
        }
        if ((top - 0x0010 & 0xffff) >= 0x7ff0 - 0x0010) {
            // x < 0x1p-1022, inf or nan
            if (ix * 2 == 0) {
                return Double.NEGATIVE_INFINITY; // log(+-0)
            }
            if (ix == 0x7ff0000000000000L) {
                return x; // log(inf) = inf
            }
            if ((top & 0x8000) != 0 || (top & 0x7ff0) == 0x7ff0) {
                return Double.NaN; // negative or NaN
            }
            // subnormal: normalize
            ix = Double.doubleToRawLongBits(x * 0x1p52);
            ix -= 52L << 52;
        }

        // x = 2^k z, z in [OFF, 2*OFF); the i-th subinterval contains z
        long tmp = ix - LOG_OFF;
        int i = (int) ((tmp >>> (52 - LOG_TABLE_BITS)) & (LOG_N - 1));
        int k = (int) (tmp >> 52); // arithmetic shift
        long iz = ix - (tmp & (0xfffL << 52));
        double invc = Double.longBitsToDouble(LOG_TAB[2 * i]);
        double logc = Double.longBitsToDouble(LOG_TAB[2 * i + 1]);
        double z = Double.longBitsToDouble(iz);

        // log(x) = log1p(z/c-1) + log(c) + k*Ln2 (r via fma, per __FP_FAST_FMA)
        double r = Math.fma(z, invc, -1.0);
        double kd = k;
        double w = Math.fma(kd, LOG_LN2HI, logc);
        double hi = w + r;
        double lo = Math.fma(kd, LOG_LN2LO, w - hi + r);
        double r2 = r * r;
        double r3 = r * r2;
        return Math.fma(r3,
                Math.fma(r2, Math.fma(r, LOG_A4, LOG_A3), Math.fma(r, LOG_A2, LOG_A1)),
                Math.fma(r2, LOG_A0, lo)) + hi;
    }

    // ================================================================ erf ==

    private static final double ERF_TINY = 1e-300;
    private static final double ERX = 8.45062911510467529297e-01;
    private static final double EFX = 1.28379167095512586316e-01;
    private static final double[] PP = {
        1.28379167095512558561e-01, -3.25042107247001499370e-01,
        -2.84817495755985104766e-02, -5.77027029648944159157e-03,
        -2.37630166566501626084e-05,
    };
    private static final double[] QQ = {
        0.0, 3.97917223959155352819e-01, 6.50222499887672944485e-02,
        5.08130628187576562776e-03, 1.32494738004321644526e-04,
        -3.96022827877536812320e-06,
    };
    private static final double[] PA = {
        -2.36211856075265944077e-03, 4.14856118683748331666e-01,
        -3.72207876035701323847e-01, 3.18346619901161753674e-01,
        -1.10894694282396677476e-01, 3.54783043256182359371e-02,
        -2.16637559486879084300e-03,
    };
    private static final double[] QA = {
        0.0, 1.06420880400844228286e-01, 5.40397917702171048937e-01,
        7.18286544141962662868e-02, 1.26171219808761642112e-01,
        1.36370839120290507362e-02, 1.19844998467991074170e-02,
    };
    private static final double[] RA = {
        -9.86494403484714822705e-03, -6.93858572707181764372e-01,
        -1.05586262253232909814e+01, -6.23753324503260060396e+01,
        -1.62396669462573470355e+02, -1.84605092906711035994e+02,
        -8.12874355063065934246e+01, -9.81432934416914548592e+00,
    };
    private static final double[] SA = {
        0.0, 1.96512716674392571292e+01, 1.37657754143519042600e+02,
        4.34565877475229228821e+02, 6.45387271733267880336e+02,
        4.29008140027567833386e+02, 1.08635005541779435134e+02,
        6.57024977031928170135e+00, -6.04244152148580987438e-02,
    };
    private static final double[] RB = {
        -9.86494292470009928597e-03, -7.99283237680523006574e-01,
        -1.77579549177547519889e+01, -1.60636384855821916062e+02,
        -6.37566443368389627722e+02, -1.02509513161107724954e+03,
        -4.83519191608651397019e+02,
    };
    private static final double[] SB = {
        0.0, 3.03380607434824582924e+01, 3.25792512996573918826e+02,
        1.53672958608443695994e+03, 3.19985821950859553908e+03,
        2.55305040643316442583e+03, 4.74528541206955367215e+02,
        -2.24409524465858183362e+01,
    };

    /**
     * Error function, bit-identical to glibc 2.39 {@code erf} (fdlibm-derived
     * {@code s_erf.c}; x86-64 compiles it without FMA contraction).
     *
     * @param x argument
     * @return erf(x) rounded to nearest double exactly as glibc rounds it
     */
    public static double erf(double x) {
        long bits = Double.doubleToRawLongBits(x);
        int hx = (int) (bits >>> 32);
        int ix = hx & 0x7fffffff;
        if (ix >= 0x7ff00000) { // erf(nan) = nan, erf(+-inf) = +-1
            int i = (hx >>> 31) << 1;
            return (double) (1 - i) + 1.0 / x;
        }
        if (ix < 0x3feb0000) { // |x| < 0.84375
            if (ix < 0x3e300000) { // |x| < 2^-28
                if (ix < 0x00800000) {
                    return 0.0625 * (16.0 * x + (16.0 * EFX) * x);
                }
                return x + EFX * x;
            }
            double z = x * x;
            double r1 = PP[0] + z * PP[1];
            double z2 = z * z;
            double r2 = PP[2] + z * PP[3];
            double z4 = z2 * z2;
            double s1 = 1.0 + z * QQ[1];
            double s2 = QQ[2] + z * QQ[3];
            double s3 = QQ[4] + z * QQ[5];
            double r = r1 + z2 * r2 + z4 * PP[4];
            double s = s1 + z2 * s2 + z4 * s3;
            double y = r / s;
            return x + x * y;
        }
        if (ix < 0x3ff40000) { // 0.84375 <= |x| < 1.25
            double s = Math.abs(x) - 1.0;
            double p1 = PA[0] + s * PA[1];
            double s2 = s * s;
            double q1 = 1.0 + s * QA[1];
            double s4 = s2 * s2;
            double p2 = PA[2] + s * PA[3];
            double s6 = s4 * s2;
            double q2 = QA[2] + s * QA[3];
            double p3 = PA[4] + s * PA[5];
            double q3 = QA[4] + s * QA[5];
            double p4 = PA[6];
            double q4 = QA[6];
            double p = p1 + s2 * p2 + s4 * p3 + s6 * p4;
            double q = q1 + s2 * q2 + s4 * q3 + s6 * q4;
            return hx >= 0 ? ERX + p / q : -ERX - p / q;
        }
        if (ix >= 0x40180000) { // |x| >= 6
            return hx >= 0 ? 1.0 - ERF_TINY : ERF_TINY - 1.0;
        }
        double ax = Math.abs(x);
        double s = 1.0 / (ax * ax);
        double bigR;
        double bigS;
        if (ix < 0x4006DB6E) { // |x| < 1/0.35
            double r1 = RA[0] + s * RA[1];
            double s2 = s * s;
            double s1 = 1.0 + s * SA[1];
            double s4 = s2 * s2;
            double r2 = RA[2] + s * RA[3];
            double s6 = s4 * s2;
            double t2 = SA[2] + s * SA[3];
            double s8 = s4 * s4;
            double r3 = RA[4] + s * RA[5];
            double t3 = SA[4] + s * SA[5];
            double r4 = RA[6] + s * RA[7];
            double t4 = SA[6] + s * SA[7];
            bigR = r1 + s2 * r2 + s4 * r3 + s6 * r4;
            bigS = s1 + s2 * t2 + s4 * t3 + s6 * t4 + s8 * SA[8];
        } else { // |x| >= 1/0.35
            double r1 = RB[0] + s * RB[1];
            double s2 = s * s;
            double s1 = 1.0 + s * SB[1];
            double s4 = s2 * s2;
            double r2 = RB[2] + s * RB[3];
            double s6 = s4 * s2;
            double t2 = SB[2] + s * SB[3];
            double r3 = RB[4] + s * RB[5];
            double t3 = SB[4] + s * SB[5];
            double t4 = SB[6] + s * SB[7];
            bigR = r1 + s2 * r2 + s4 * r3 + s6 * RB[6];
            bigS = s1 + s2 * t2 + s4 * t3 + s6 * t4;
        }
        double z = Double.longBitsToDouble(
                Double.doubleToRawLongBits(ax) & 0xffffffff00000000L);
        double r = exp(-z * z - 0.5625) * exp((z - ax) * (z + ax) + bigR / bigS);
        return hx >= 0 ? 1.0 - r / ax : r / ax - 1.0;
    }

    // ======================================================= data tables ==
    // ---- exp data (glibc sysdeps/ieee754/dbl-64/e_exp_data.c) ----
    private static final double EXP_INVLN2N = Double.longBitsToDouble(0x40671547652b82feL);
    private static final double EXP_SHIFT = Double.longBitsToDouble(0x4338000000000000L);
    private static final double EXP_NEGLN2HIN = Double.longBitsToDouble(0xbf762e42fefa0000L);
    private static final double EXP_NEGLN2LON = Double.longBitsToDouble(0xbd0cf79abc9e3b3aL);
    private static final double EXP_C2 = Double.longBitsToDouble(0x3fdffffffffffdbdL);
    private static final double EXP_C3 = Double.longBitsToDouble(0x3fc555555555543cL);
    private static final double EXP_C4 = Double.longBitsToDouble(0x3fa55555cf172b91L);
    private static final double EXP_C5 = Double.longBitsToDouble(0x3f81111167a4d017L);
    private static final long[] EXP_TAB = {
        0x0000000000000000L, 0x3ff0000000000000L, 0x3c9b3b4f1a88bf6eL, 0x3feff63da9fb3335L,
        0xbc7160139cd8dc5dL, 0x3fefec9a3e778061L, 0xbc905e7a108766d1L, 0x3fefe315e86e7f85L,
        0x3c8cd2523567f613L, 0x3fefd9b0d3158574L, 0xbc8bce8023f98efaL, 0x3fefd06b29ddf6deL,
        0x3c60f74e61e6c861L, 0x3fefc74518759bc8L, 0x3c90a3e45b33d399L, 0x3fefbe3ecac6f383L,
        0x3c979aa65d837b6dL, 0x3fefb5586cf9890fL, 0x3c8eb51a92fdeffcL, 0x3fefac922b7247f7L,
        0x3c3ebe3d702f9cd1L, 0x3fefa3ec32d3d1a2L, 0xbc6a033489906e0bL, 0x3fef9b66affed31bL,
        0xbc9556522a2fbd0eL, 0x3fef9301d0125b51L, 0xbc5080ef8c4eea55L, 0x3fef8abdc06c31ccL,
        0xbc91c923b9d5f416L, 0x3fef829aaea92de0L, 0x3c80d3e3e95c55afL, 0x3fef7a98c8a58e51L,
        0xbc801b15eaa59348L, 0x3fef72b83c7d517bL, 0xbc8f1ff055de323dL, 0x3fef6af9388c8deaL,
        0x3c8b898c3f1353bfL, 0x3fef635beb6fcb75L, 0xbc96d99c7611eb26L, 0x3fef5be084045cd4L,
        0x3c9aecf73e3a2f60L, 0x3fef54873168b9aaL, 0xbc8fe782cb86389dL, 0x3fef4d5022fcd91dL,
        0x3c8a6f4144a6c38dL, 0x3fef463b88628cd6L, 0x3c807a05b0e4047dL, 0x3fef3f49917ddc96L,
        0x3c968efde3a8a894L, 0x3fef387a6e756238L, 0x3c875e18f274487dL, 0x3fef31ce4fb2a63fL,
        0x3c80472b981fe7f2L, 0x3fef2b4565e27cddL, 0xbc96b87b3f71085eL, 0x3fef24dfe1f56381L,
        0x3c82f7e16d09ab31L, 0x3fef1e9df51fdee1L, 0xbc3d219b1a6fbffaL, 0x3fef187fd0dad990L,
        0x3c8b3782720c0ab4L, 0x3fef1285a6e4030bL, 0x3c6e149289cecb8fL, 0x3fef0cafa93e2f56L,
        0x3c834d754db0abb6L, 0x3fef06fe0a31b715L, 0x3c864201e2ac744cL, 0x3fef0170fc4cd831L,
        0x3c8fdd395dd3f84aL, 0x3feefc08b26416ffL, 0xbc86a3803b8e5b04L, 0x3feef6c55f929ff1L,
        0xbc924aedcc4b5068L, 0x3feef1a7373aa9cbL, 0xbc9907f81b512d8eL, 0x3feeecae6d05d866L,
        0xbc71d1e83e9436d2L, 0x3feee7db34e59ff7L, 0xbc991919b3ce1b15L, 0x3feee32dc313a8e5L,
        0x3c859f48a72a4c6dL, 0x3feedea64c123422L, 0xbc9312607a28698aL, 0x3feeda4504ac801cL,
        0xbc58a78f4817895bL, 0x3feed60a21f72e2aL, 0xbc7c2c9b67499a1bL, 0x3feed1f5d950a897L,
        0x3c4363ed60c2ac11L, 0x3feece086061892dL, 0x3c9666093b0664efL, 0x3feeca41ed1d0057L,
        0x3c6ecce1daa10379L, 0x3feec6a2b5c13cd0L, 0x3c93ff8e3f0f1230L, 0x3feec32af0d7d3deL,
        0x3c7690cebb7aafb0L, 0x3feebfdad5362a27L, 0x3c931dbdeb54e077L, 0x3feebcb299fddd0dL,
        0xbc8f94340071a38eL, 0x3feeb9b2769d2ca7L, 0xbc87deccdc93a349L, 0x3feeb6daa2cf6642L,
        0xbc78dec6bd0f385fL, 0x3feeb42b569d4f82L, 0xbc861246ec7b5cf6L, 0x3feeb1a4ca5d920fL,
        0x3c93350518fdd78eL, 0x3feeaf4736b527daL, 0x3c7b98b72f8a9b05L, 0x3feead12d497c7fdL,
        0x3c9063e1e21c5409L, 0x3feeab07dd485429L, 0x3c34c7855019c6eaL, 0x3feea9268a5946b7L,
        0x3c9432e62b64c035L, 0x3feea76f15ad2148L, 0xbc8ce44a6199769fL, 0x3feea5e1b976dc09L,
        0xbc8c33c53bef4da8L, 0x3feea47eb03a5585L, 0xbc845378892be9aeL, 0x3feea34634ccc320L,
        0xbc93cedd78565858L, 0x3feea23882552225L, 0x3c5710aa807e1964L, 0x3feea155d44ca973L,
        0xbc93b3efbf5e2228L, 0x3feea09e667f3bcdL, 0xbc6a12ad8734b982L, 0x3feea012750bdabfL,
        0xbc6367efb86da9eeL, 0x3fee9fb23c651a2fL, 0xbc80dc3d54e08851L, 0x3fee9f7df9519484L,
        0xbc781f647e5a3ecfL, 0x3fee9f75e8ec5f74L, 0xbc86ee4ac08b7db0L, 0x3fee9f9a48a58174L,
        0xbc8619321e55e68aL, 0x3fee9feb564267c9L, 0x3c909ccb5e09d4d3L, 0x3feea0694fde5d3fL,
        0xbc7b32dcb94da51dL, 0x3feea11473eb0187L, 0x3c94ecfd5467c06bL, 0x3feea1ed0130c132L,
        0x3c65ebe1abd66c55L, 0x3feea2f336cf4e62L, 0xbc88a1c52fb3cf42L, 0x3feea427543e1a12L,
        0xbc9369b6f13b3734L, 0x3feea589994cce13L, 0xbc805e843a19ff1eL, 0x3feea71a4623c7adL,
        0xbc94d450d872576eL, 0x3feea8d99b4492edL, 0x3c90ad675b0e8a00L, 0x3feeaac7d98a6699L,
        0x3c8db72fc1f0eab4L, 0x3feeace5422aa0dbL, 0xbc65b6609cc5e7ffL, 0x3feeaf3216b5448cL,
        0x3c7bf68359f35f44L, 0x3feeb1ae99157736L, 0xbc93091fa71e3d83L, 0x3feeb45b0b91ffc6L,
        0xbc5da9b88b6c1e29L, 0x3feeb737b0cdc5e5L, 0xbc6c23f97c90b959L, 0x3feeba44cbc8520fL,
        0xbc92434322f4f9aaL, 0x3feebd829fde4e50L, 0xbc85ca6cd7668e4bL, 0x3feec0f170ca07baL,
        0x3c71affc2b91ce27L, 0x3feec49182a3f090L, 0x3c6dd235e10a73bbL, 0x3feec86319e32323L,
        0xbc87c50422622263L, 0x3feecc667b5de565L, 0x3c8b1c86e3e231d5L, 0x3feed09bec4a2d33L,
        0xbc91bbd1d3bcbb15L, 0x3feed503b23e255dL, 0x3c90cc319cee31d2L, 0x3feed99e1330b358L,
        0x3c8469846e735ab3L, 0x3feede6b5579fdbfL, 0xbc82dfcd978e9db4L, 0x3feee36bbfd3f37aL,
        0x3c8c1a7792cb3387L, 0x3feee89f995ad3adL, 0xbc907b8f4ad1d9faL, 0x3feeee07298db666L,
        0xbc55c3d956dcaebaL, 0x3feef3a2b84f15fbL, 0xbc90a40e3da6f640L, 0x3feef9728de5593aL,
        0xbc68d6f438ad9334L, 0x3feeff76f2fb5e47L, 0xbc91eee26b588a35L, 0x3fef05b030a1064aL,
        0x3c74ffd70a5fddcdL, 0x3fef0c1e904bc1d2L, 0xbc91bdfbfa9298acL, 0x3fef12c25bd71e09L,
        0x3c736eae30af0cb3L, 0x3fef199bdd85529cL, 0x3c8ee3325c9ffd94L, 0x3fef20ab5fffd07aL,
        0x3c84e08fd10959acL, 0x3fef27f12e57d14bL, 0x3c63cdaf384e1a67L, 0x3fef2f6d9406e7b5L,
        0x3c676b2c6c921968L, 0x3fef3720dcef9069L, 0xbc808a1883ccb5d2L, 0x3fef3f0b555dc3faL,
        0xbc8fad5d3ffffa6fL, 0x3fef472d4a07897cL, 0xbc900dae3875a949L, 0x3fef4f87080d89f2L,
        0x3c74a385a63d07a7L, 0x3fef5818dcfba487L, 0xbc82919e2040220fL, 0x3fef60e316c98398L,
        0x3c8e5a50d5c192acL, 0x3fef69e603db3285L, 0x3c843a59ac016b4bL, 0x3fef7321f301b460L,
        0xbc82d52107b43e1fL, 0x3fef7c97337b9b5fL, 0xbc892ab93b470dc9L, 0x3fef864614f5a129L,
        0x3c74b604603a88d3L, 0x3fef902ee78b3ff6L, 0x3c83c5ec519d7271L, 0x3fef9a51fbc74c83L,
        0xbc8ff7128fd391f0L, 0x3fefa4afa2a490daL, 0xbc8dae98e223747dL, 0x3fefaf482d8e67f1L,
        0x3c8ec3bc41aa2008L, 0x3fefba1bee615a27L, 0x3c842b94c3a9eb32L, 0x3fefc52b376bba97L,
        0x3c8a64a931d185eeL, 0x3fefd0765b6e4540L, 0xbc8e37bae43be3edL, 0x3fefdbfdad9cbe14L,
        0x3c77893b4d91cd9dL, 0x3fefe7c1819e90d8L, 0x3c5305c14160cc89L, 0x3feff3c22b8f71f1L,
    };

    // ---- log data (glibc sysdeps/ieee754/dbl-64/e_log_data.c) ----
    private static final double LOG_LN2HI = Double.longBitsToDouble(0x3fe62e42fefa3800L);
    private static final double LOG_LN2LO = Double.longBitsToDouble(0x3d2ef35793c76730L);
    private static final double LOG_A0 = Double.longBitsToDouble(0xbfe0000000000001L);
    private static final double LOG_A1 = Double.longBitsToDouble(0x3fd555555551305bL);
    private static final double LOG_A2 = Double.longBitsToDouble(0xbfcfffffffeb4590L);
    private static final double LOG_A3 = Double.longBitsToDouble(0x3fc999b324f10111L);
    private static final double LOG_A4 = Double.longBitsToDouble(0xbfc55575e506c89fL);
    private static final double LOG_B0 = Double.longBitsToDouble(0xbfe0000000000000L);
    private static final double LOG_B1 = Double.longBitsToDouble(0x3fd5555555555577L);
    private static final double LOG_B2 = Double.longBitsToDouble(0xbfcffffffffffdcbL);
    private static final double LOG_B3 = Double.longBitsToDouble(0x3fc999999995dd0cL);
    private static final double LOG_B4 = Double.longBitsToDouble(0xbfc55555556745a7L);
    private static final double LOG_B5 = Double.longBitsToDouble(0x3fc24924a344de30L);
    private static final double LOG_B6 = Double.longBitsToDouble(0xbfbfffffa4423d65L);
    private static final double LOG_B7 = Double.longBitsToDouble(0x3fbc7184282ad6caL);
    private static final double LOG_B8 = Double.longBitsToDouble(0xbfb999eb43b068ffL);
    private static final double LOG_B9 = Double.longBitsToDouble(0x3fb78182f7afd085L);
    private static final double LOG_B10 = Double.longBitsToDouble(0xbfb5521375d145cdL);
    /** invc/logc pairs, 2 longs per table row. */
    private static final long[] LOG_TAB = {
        0x3ff734f0c3e0de9fL, 0xbfd7cc7f79e69000L,
        0x3ff713786a2ce91fL, 0xbfd76feec20d0000L,
        0x3ff6f26008fab5a0L, 0xbfd713e31351e000L,
        0x3ff6d1a61f138c7dL, 0xbfd6b85b38287800L,
        0x3ff6b1490bc5b4d1L, 0xbfd65d5590807800L,
        0x3ff69147332f0cbaL, 0xbfd602d076180000L,
        0x3ff6719f18224223L, 0xbfd5a8ca86909000L,
        0x3ff6524f99a51ed9L, 0xbfd54f4356035000L,
        0x3ff63356aa8f24c4L, 0xbfd4f637c36b4000L,
        0x3ff614b36b9ddc14L, 0xbfd49da7fda85000L,
        0x3ff5f66452c65c4cL, 0xbfd445923989a800L,
        0x3ff5d867b5912c4fL, 0xbfd3edf439b0b800L,
        0x3ff5babccb5b90deL, 0xbfd396ce448f7000L,
        0x3ff59d61f2d91a78L, 0xbfd3401e17bda000L,
        0x3ff5805612465687L, 0xbfd2e9e2ef468000L,
        0x3ff56397cee76bd3L, 0xbfd2941b3830e000L,
        0x3ff54725e2a77f93L, 0xbfd23ec58cda8800L,
        0x3ff52aff42064583L, 0xbfd1e9e129279000L,
        0x3ff50f22dbb2bddfL, 0xbfd1956d2b48f800L,
        0x3ff4f38f4734ded7L, 0xbfd141679ab9f800L,
        0x3ff4d843cfde2840L, 0xbfd0edd094ef9800L,
        0x3ff4bd3ec078a3c8L, 0xbfd09aa518db1000L,
        0x3ff4a27fc3e0258aL, 0xbfd047e65263b800L,
        0x3ff4880524d48434L, 0xbfcfeb224586f000L,
        0x3ff46dce1b192d0bL, 0xbfcf474a7517b000L,
        0x3ff453d9d3391854L, 0xbfcea4443d103000L,
        0x3ff43a2744b4845aL, 0xbfce020d44e9b000L,
        0x3ff420b54115f8fbL, 0xbfcd60a22977f000L,
        0x3ff40782da3ef4b1L, 0xbfccc00104959000L,
        0x3ff3ee8f5d57fe8fL, 0xbfcc202956891000L,
        0x3ff3d5d9a00b4ce9L, 0xbfcb81178d811000L,
        0x3ff3bd60c010c12bL, 0xbfcae2c9ccd3d000L,
        0x3ff3a5242b75dab8L, 0xbfca45402e129000L,
        0x3ff38d22cd9fd002L, 0xbfc9a877681df000L,
        0x3ff3755bc5847a1cL, 0xbfc90c6d69483000L,
        0x3ff35dce49ad36e2L, 0xbfc87120a645c000L,
        0x3ff34679984dd440L, 0xbfc7d68fb4143000L,
        0x3ff32f5cceffcb24L, 0xbfc73cb83c627000L,
        0x3ff3187775a10d49L, 0xbfc6a39a9b376000L,
        0x3ff301c8373e3990L, 0xbfc60b3154b7a000L,
        0x3ff2eb4ebb95f841L, 0xbfc5737d76243000L,
        0x3ff2d50a0219a9d1L, 0xbfc4dc7b8fc23000L,
        0x3ff2bef9a8b7fd2aL, 0xbfc4462c51d20000L,
        0x3ff2a91c7a0c1babL, 0xbfc3b08abc830000L,
        0x3ff293726014b530L, 0xbfc31b996b490000L,
        0x3ff27dfa5757a1f5L, 0xbfc2875490a44000L,
        0x3ff268b39b1d3bbfL, 0xbfc1f3b9f879a000L,
        0x3ff2539d838ff5bdL, 0xbfc160c8252ca000L,
        0x3ff23eb7aac9083bL, 0xbfc0ce7f57f72000L,
        0x3ff22a012ba940b6L, 0xbfc03cdc49fea000L,
        0x3ff2157996cc4132L, 0xbfbf57bdbc4b8000L,
        0x3ff201201dd2fc9bL, 0xbfbe370896404000L,
        0x3ff1ecf4494d480bL, 0xbfbd17983ef94000L,
        0x3ff1d8f5528f6569L, 0xbfbbf9674ed8a000L,
        0x3ff1c52311577e7cL, 0xbfbadc79202f6000L,
        0x3ff1b17c74cb26e9L, 0xbfb9c0c3e7288000L,
        0x3ff19e010c2c1ab6L, 0xbfb8a646b372c000L,
        0x3ff18ab07bb670bdL, 0xbfb78d01b3ac0000L,
        0x3ff1778a25efbcb6L, 0xbfb674f145380000L,
        0x3ff1648d354c31daL, 0xbfb55e0e6d878000L,
        0x3ff151b990275fddL, 0xbfb4485cdea1e000L,
        0x3ff13f0ea432d24cL, 0xbfb333d94d6aa000L,
        0x3ff12c8b7210f9daL, 0xbfb22079f8c56000L,
        0x3ff11a3028ecb531L, 0xbfb10e4698622000L,
        0x3ff107fbda8434afL, 0xbfaffa6c6ad20000L,
        0x3ff0f5ee0f4e6bb3L, 0xbfadda8d4a774000L,
        0x3ff0e4065d2a9fceL, 0xbfabbcece4850000L,
        0x3ff0d244632ca521L, 0xbfa9a1894012c000L,
        0x3ff0c0a77ce2981aL, 0xbfa788583302c000L,
        0x3ff0af2f83c636d1L, 0xbfa5715e67d68000L,
        0x3ff09ddb98a01339L, 0xbfa35c8a49658000L,
        0x3ff08cabaf52e7dfL, 0xbfa149e364154000L,
        0x3ff07b9f2f4e28fbL, 0xbf9e72c082eb8000L,
        0x3ff06ab58c358f19L, 0xbf9a55f152528000L,
        0x3ff059eea5ecf92cL, 0xbf963d62cf818000L,
        0x3ff04949cdd12c90L, 0xbf9228fb8caa0000L,
        0x3ff038c6c6f0ada9L, 0xbf8c317b20f90000L,
        0x3ff02865137932a9L, 0xbf8419355daa0000L,
        0x3ff0182427ea7348L, 0xbf781203c2ec0000L,
        0x3ff008040614b195L, 0xbf60040979240000L,
        0x3fefe01ff726fa1aL, 0x3f6feff384900000L,
        0x3fefa11cc261ea74L, 0x3f87dc41353d0000L,
        0x3fef6310b081992eL, 0x3f93cea3c4c28000L,
        0x3fef25f63ceeadcdL, 0x3f9b9fc114890000L,
        0x3feee9c8039113e7L, 0x3fa1b0d8ce110000L,
        0x3feeae8078cbb1abL, 0x3fa58a5bd001c000L,
        0x3fee741aa29d0c9bL, 0x3fa95c8340d88000L,
        0x3fee3a91830a99b5L, 0x3fad276aef578000L,
        0x3fee01e009609a56L, 0x3fb07598e598c000L,
        0x3fedca01e577bb98L, 0x3fb253f5e30d2000L,
        0x3fed92f20b7c9103L, 0x3fb42edd8b380000L,
        0x3fed5cac66fb5cceL, 0x3fb606598757c000L,
        0x3fed272caa5ede9dL, 0x3fb7da76356a0000L,
        0x3fecf26e3e6b2ccdL, 0x3fb9ab434e1c6000L,
        0x3fecbe6da2a77902L, 0x3fbb78c7bb0d6000L,
        0x3fec8b266d37086dL, 0x3fbd431332e72000L,
        0x3fec5894bd5d5804L, 0x3fbf0a3171de6000L,
        0x3fec26b533bb9f8cL, 0x3fc067152b914000L,
        0x3febf583eeece73fL, 0x3fc147858292b000L,
        0x3febc4fd75db96c1L, 0x3fc2266ecdca3000L,
        0x3feb951e0c864a28L, 0x3fc303d7a6c55000L,
        0x3feb65e2c5ef3e2cL, 0x3fc3dfc33c331000L,
        0x3feb374867c9888bL, 0x3fc4ba366b7a8000L,
        0x3feb094b211d304aL, 0x3fc5933928d1f000L,
        0x3feadbe885f2ef7eL, 0x3fc66acd2418f000L,
        0x3feaaf1d31603da2L, 0x3fc740f8ec669000L,
        0x3fea82e63fd358a7L, 0x3fc815c0f51af000L,
        0x3fea5740ef09738bL, 0x3fc8e92954f68000L,
        0x3fea2c2a90ab4b27L, 0x3fc9bb3602f84000L,
        0x3fea01a01393f2d1L, 0x3fca8bed1c2c0000L,
        0x3fe9d79f24db3c1bL, 0x3fcb5b515c01d000L,
        0x3fe9ae2505c7b190L, 0x3fcc2967ccbcc000L,
        0x3fe9852ef297ce2fL, 0x3fccf635d5486000L,
        0x3fe95cbaeea44b75L, 0x3fcdc1bd3446c000L,
        0x3fe934c69de74838L, 0x3fce8c01b8cfe000L,
        0x3fe90d4f2f6752e6L, 0x3fcf5509c0179000L,
        0x3fe8e6528effd79dL, 0x3fd00e6c121fb800L,
        0x3fe8bfce9fcc007cL, 0x3fd071b80e93d000L,
        0x3fe899c0dabec30eL, 0x3fd0d46b9e867000L,
        0x3fe87427aa2317fbL, 0x3fd13687334bd000L,
        0x3fe84f00acb39a08L, 0x3fd1980d67234800L,
        0x3fe82a49e8653e55L, 0x3fd1f8ffe0cc8000L,
        0x3fe8060195f40260L, 0x3fd2595fd7636800L,
        0x3fe7e22563e0a329L, 0x3fd2b9300914a800L,
        0x3fe7beb377dcb5adL, 0x3fd3187210436000L,
        0x3fe79baa679725c2L, 0x3fd377266dec1800L,
        0x3fe77907f2170657L, 0x3fd3d54ffbaf3000L,
        0x3fe756cadbd6130cL, 0x3fd432eee32fe000L,
    };}
