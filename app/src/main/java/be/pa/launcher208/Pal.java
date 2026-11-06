package be.pa.launcher208;

/** Couleurs de l'interface, version jour et version nuit (reprises des maquettes). */
final class Pal {
    static boolean night;

    static int bg, card, card2, track, tabsBg, tabOn, text, text2, text3, accent, accentSoft, divider;
    static int railOnBg, railOnFg, c0, c1, c2;
    static int chipB, chipBf, chipG, chipGf, chipO, chipOf;
    static int warnBg, warnLine, warnText, ok, bad, teal;
    static float carDim;
    static boolean shadows;

    // Couleurs fixes (identiques jour et nuit)
    static final int BLUE = 0xFF2563EB, BLUE_D = 0xFF1A47B8, BLUE_L = 0xFF4C8DFF, BLUE_LL = 0xFF6EA2FF;
    static final int ORANGE = 0xFFF59E0B, ORANGE_D = 0xFFB45309, AMBER_LINE = 0xFFF5A524;
    static final int WHITE = 0xFFFFFFFF;

    static void set(boolean n) {
        night = n;
        if (!n) {
            bg = 0xFFE6E9ED; card = 0xFFFFFFFF; card2 = 0xFFEEF1F4; track = 0xFFE6E9ED;
            tabsBg = 0xFFE3E7EB; tabOn = 0xFFFFFFFF; text = 0xFF0F1216; text2 = 0xFF56606B; text3 = 0xFF8A929C;
            accent = BLUE; accentSoft = 0xFFE6EEFE; divider = 0xFFECEFF2;
            railOnBg = 0xFF15181C; railOnFg = 0xFFFFFFFF;
            c0 = 0xFFFFFFFF; c1 = 0xFFF2F4F7; c2 = 0xFFE2E6EA;
            chipB = 0xFFE6EEFE; chipBf = BLUE; chipG = 0xFFE3F5EA; chipGf = 0xFF15803D; chipO = 0xFFFDEFDF; chipOf = 0xFFB45309;
            warnBg = 0xFFFFF4DE; warnLine = AMBER_LINE; warnText = 0xFF7A3E00;
            ok = 0xFF15803D; bad = 0xFFDC2626; teal = 0xFF0EA5A4;
            carDim = 1f; shadows = true;
        } else {
            bg = 0xFF0A0B0D; card = 0xFF16181C; card2 = 0xFF23272D; track = 0xFF2A2E35;
            tabsBg = 0xFF1C1F24; tabOn = 0xFF2A2E35; text = 0xFFF2F3F5; text2 = 0xFFA7ADB5; text3 = 0xFF7A818A;
            accent = BLUE_L; accentSoft = 0xFF1C2638; divider = 0xFF23272D;
            railOnBg = 0xFFF2F3F5; railOnFg = 0xFF0A0B0D;
            c0 = 0xFF2A2E35; c1 = 0xFF17191D; c2 = 0xFF0E1013;
            chipB = 0xFF1C2638; chipBf = 0xFF7AA7FF; chipG = 0xFF17281E; chipGf = 0xFF6EDC93; chipO = 0xFF2E2216; chipOf = 0xFFF5B266;
            warnBg = 0xFF2E2216; warnLine = AMBER_LINE; warnText = 0xFFF5B266;
            ok = 0xFF6EDC93; bad = 0xFFF87171; teal = 0xFF2DD4BF;
            carDim = 0.8f; shadows = false;
        }
    }
}
