package com.eyerest.app.ledger;

/** Reserve room for an entire icon, not just its centre, outside the ring. */
final class RingIconLayout {
    static float radius(float width, float height, float preferred,
                        float orbitGap, float iconSize, float margin) {
        return Math.max(0, Math.min(preferred,
                Math.min(width, height) / 2 - orbitGap - iconSize / 2 - margin));
    }

    static float clampCenter(float center, float extent, float iconSize, float margin) {
        float inset = iconSize / 2 + margin;
        if (extent < inset * 2) return extent / 2;
        return Math.max(inset, Math.min(extent - inset, center));
    }
}
