package com.mystipixel.royalregen.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** Legacy '&' colour strings to Adventure components. */
public final class Text {

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    private Text() {
    }

    public static Component chat(String input) {
        return AMP.deserialize(input == null ? "" : input);
    }
}
