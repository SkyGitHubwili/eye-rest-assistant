package com.eyerest.app.ledger;

public final class RingIconLayoutTest {
    public static void main(String[] args) {
        // The old 252dp chart put part of an icon below its own canvas.
        if (126 + 252 * .4 + 23 + 21 / 2.0 <= 252) throw new AssertionError("Missing regression fixture");
        int cases = 0;
        for (float density : new float[]{1, 1.5f, 2, 2.75f, 3.5f, 4}) {
            for (int widthDp : new int[]{220, 280, 360, 600}) {
                for (int heightDp : new int[]{252, 290}) {
                    int width=Math.round(widthDp*density),height=Math.round(heightDp*density);
                    int size=Math.round(18*density),margin=Math.round(3*density),gap=Math.round(24*density);
                    float radius=RingIconLayout.radius(width,height,Math.min(width,height)*.4f,gap,size,margin);
                    for (int degrees=0;degrees<360;degrees++) {
                        double angle=Math.toRadians(degrees);
                        // Also exercise the outward displacement for crowded weekly icons.
                        for (int extra : new int[]{0, Math.round(40*density)}) {
                            float x=RingIconLayout.clampCenter(width/2f+(float)Math.cos(angle)*(radius+gap+extra),width,size,margin);
                            float y=RingIconLayout.clampCenter(height/2f+(float)Math.sin(angle)*(radius+gap+extra),height,size,margin);
                            int left=Math.round(x-size/2f),top=Math.round(y-size/2f);
                            if(left<margin || top<margin || left+size>width-margin || top+size>height-margin)
                                throw new AssertionError("Clipped icon at "+degrees+" degrees");
                            cases++;
                        }
                    }
                }
            }
        }
        System.out.println("PASS: "+cases+" ring icon bounds at all angles and six screen densities");
    }
}
