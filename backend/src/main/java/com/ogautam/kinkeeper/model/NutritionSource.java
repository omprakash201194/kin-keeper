package com.ogautam.kinkeeper.model;

/**
 * Where a nutrition entry came from. Used to hint the Claude-vision prompt
 * and to group entries in the UI.
 *   PACKAGED — store-bought item with a nutrition label on the box/wrapper.
 *   RAW      — single whole food (an apple, a cup of milk).
 *   COOKED   — prepared dish / home-cooked plate — estimates only.
 *   DRINK    — beverage (coffee, soda, juice) where macros are small but sugar matters.
 *   OTHER    — anything Claude can describe but doesn't fit the above.
 */
public enum NutritionSource {
    PACKAGED,
    RAW,
    COOKED,
    DRINK,
    OTHER
}
