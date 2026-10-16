package com.mcprovince.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link VectorShape} 的二进制编解码，存档里每个区划的几何都走它。
 *
 * <p>格式（小端）：{@code int version}, {@code int partCount}，
 * 然后每个子路径 {@code int pointCount} 加 {@code pointCount} 对 {@code double x, z}。
 * 扁平化精度由 {@link VectorShape#outlinePoints()} 决定，0.35 方格在最小可视比例下也看不出折线。</p>
 */
public final class VectorCodec {

    public static final int CODEC_VERSION = 1;

    private VectorCodec() {
    }

    public static byte[] encode(VectorShape shape) {
        List<double[]> parts = shape == null ? List.of() : shape.outlinePoints();
        int points = 0;
        for (double[] part : parts) {
            points += part.length / 2;
        }
        ByteBuffer buffer = ByteBuffer.allocate(8 + parts.size() * 4 + points * 16).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(CODEC_VERSION);
        buffer.putInt(parts.size());
        for (double[] part : parts) {
            int count = part.length / 2;
            buffer.putInt(count);
            for (int i = 0; i < count; i++) {
                buffer.putDouble(part[i * 2]);
                buffer.putDouble(part[i * 2 + 1]);
            }
        }
        return buffer.array();
    }

    public static VectorShape decode(byte[] data) {
        if (data == null || data.length < 8) {
            return VectorShape.empty();
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int version = buffer.getInt();
        if (version != CODEC_VERSION) {
            return VectorShape.empty();
        }
        int partCount = buffer.getInt();
        if (partCount <= 0 || partCount > 1_000_000) {
            return VectorShape.empty();
        }
        List<double[]> parts = new ArrayList<>(partCount);
        for (int p = 0; p < partCount; p++) {
            if (buffer.remaining() < 4) {
                break;
            }
            int count = buffer.getInt();
            if (count < 3 || buffer.remaining() < count * 16L) {
                break;
            }
            double[] part = new double[count * 2];
            for (int i = 0; i < count; i++) {
                part[i * 2] = buffer.getDouble();
                part[i * 2 + 1] = buffer.getDouble();
            }
            parts.add(part);
        }
        return VectorShape.ofPoints(parts);
    }
}
