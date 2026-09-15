package com.hct.adas;

import java.nio.ByteBuffer;

/** CPU downsampling and NV21-to-RGB conversion into a reusable model input buffer. */
public final class Nv21Preprocessor {
    private Nv21Preprocessor() {
    }

    public record Transform(int size, int width, int height, int left, int top) {
        public float sourceX(float modelX) {
            return Math.max(0f, Math.min(1f, (modelX * size - left) / width));
        }

        public float sourceY(float modelY) {
            return Math.max(0f, Math.min(1f, (modelY * size - top) / height));
        }
    }

    public static Transform transform(int width, int height, int size) {
        if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0
                || size <= 0 || (long) width * height * 3 / 2 > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid NV21 dimensions");
        }
        double scale = Math.min((double) size / width, (double) size / height);
        int resizedWidth = Math.max(1, (int) Math.round(width * scale));
        int resizedHeight = Math.max(1, (int) Math.round(height * scale));
        return new Transform(size, resizedWidth, resizedHeight,
                (size - resizedWidth) / 2, (size - resizedHeight) / 2);
    }

    public static Transform fill(byte[] nv21, int width, int height, int size, ByteBuffer rgb) {
        Transform transform = transform(width, height, size);
        if (nv21 == null || nv21.length != (long) width * height * 3 / 2
                || rgb == null || rgb.capacity() != (long) size * size * 3) {
            throw new IllegalArgumentException("NV21 or RGB buffer size mismatch");
        }
        rgb.clear();
        for (int y = 0; y < size; y++) {
            int localY = y - transform.top();
            for (int x = 0; x < size; x++) {
                int localX = x - transform.left();
                if (localY < 0 || localY >= transform.height()
                        || localX < 0 || localX >= transform.width()) {
                    rgb.put((byte) 114).put((byte) 114).put((byte) 114);
                    continue;
                }
                int sourceX = Math.min(width - 1,
                        (int) ((localX + 0.5) * width / transform.width()));
                int sourceY = Math.min(height - 1,
                        (int) ((localY + 0.5) * height / transform.height()));
                int luma = Math.max(0, (nv21[sourceY * width + sourceX] & 255) - 16);
                int chroma = width * height + (sourceY / 2) * width + (sourceX & ~1);
                int v = (nv21[chroma] & 255) - 128;
                int u = (nv21[chroma + 1] & 255) - 128;
                rgb.put(component((298 * luma + 409 * v + 128) >> 8));
                rgb.put(component((298 * luma - 100 * u - 208 * v + 128) >> 8));
                rgb.put(component((298 * luma + 516 * u + 128) >> 8));
            }
        }
        rgb.rewind();
        return transform;
    }

    private static byte component(int value) {
        return (byte) Math.max(0, Math.min(255, value));
    }
}
