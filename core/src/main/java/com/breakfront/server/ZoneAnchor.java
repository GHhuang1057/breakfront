package com.breakfront.server;

/**
 * 据点在地图上的锚点（XZ 平面圆形判定）。圆心取方块中心，仅按 X/Z 距离计算。
 */
public record ZoneAnchor(String zoneId, double x, double z, double radius) {

    public boolean contains(double px, double pz) {
        double dx = px - x;
        double dz = pz - z;
        return dx * dx + dz * dz <= radius * radius;
    }
}
