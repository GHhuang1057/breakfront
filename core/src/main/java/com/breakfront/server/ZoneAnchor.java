package com.breakfront.server;

/**
 * 据点在地图上的锚点（XZ 平面方形判定，2026-09-05 v2）。
 *
 * v1 为圆形（dx²+dz²≤r²）；用户实测视觉/语义上领地应呈现为规整的「方块区域」，
 * 服务端占点判定与客户端方形描边统一：|dx|≤r 且 |dz|≤r（外接正方形）。
 * 字段 radius 语义 = 半边长 half-extent（保留字段名以兼容 sectors.json 数据，
 * 解析/编辑器照常读写 radius 即该方格的半边长）。
 */
public record ZoneAnchor(String zoneId, double x, double z, double radius) {

    public boolean contains(double px, double pz) {
        double dx = px - x;
        double dz = pz - z;
        return Math.abs(dx) <= radius && Math.abs(dz) <= radius;
    }
}
