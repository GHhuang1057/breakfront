package com.breakfront.server.arena;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 高架走廊地图装载器（MVP）。
 *
 * 读取 jar 内置体素地图 JSON（maps/tools/export_voxel.py 产出），在服务端世界
 * 以「列柱 + 贴地元素」方式实建城市。代码表：
 *   0=地面 1=道路 2=广场 3=掩体 4=高架路面 5=高架立柱 6=楼宇（h 列生效）
 *
 * 说明：MVP 用整列实心楼体保证远看轮廓清晰；后续换 WorldEdit/结构快照管线
 * 做真实立面（门窗/室内），并接入每局回滚。
 */
public final class ArenaViaduct {

    private static final int ROAD_LAYER = 8;      // 高架高于地面层数
    private static final int[] EMPTY = new int[0];

    private ArenaViaduct() {
    }

    /** 首次服务端 tick 调用（世界已可加载区块）。返回是否建成。 */
    public static boolean tryBuild(ServerWorld world) {
        int[] k;
        int[] h;
        try (InputStream in = ArenaViaduct.class.getResourceAsStream("/breakfront/maps/viaduct.json")) {
            if (in == null) {
                return false;
            }
            String text;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                text = sb.toString();
            }
            k = parseArray(text, "\"k\":[");
            h = parseArray(text, "\"h\":[");
        } catch (Exception e) {
            return false;
        }
        if (k.length == 0 || h.length != k.length) {
            return false;
        }
        int size = (int) Math.sqrt(k.length); // 96
        int[] counts = new int[7];

        // 预加载覆盖区域所有区块，避免 setBlockState 写到未加载区块
        int minCx = 0 >> 4;
        int maxCx = (size - 1) >> 4;
        int minCz = 0 >> 4;
        int maxCz = (size - 1) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                world.getChunk(cx, cz);
            }
        }

        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int idx = z * size + x;
                int code = k[idx];
                int height = h[idx];
                int top = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                counts[code]++;
                switch (code) {
                    case 1 -> setSurface(world, x, z, top, Blocks.BLACK_CONCRETE);
                    case 2 -> setSurface(world, x, z, top, Blocks.SANDSTONE);
                    case 3 -> setSurface(world, x, z, top, Blocks.MOSSY_COBBLESTONE);
                    case 5 -> { // 立柱：从地表面上一层到高架底
                        int bottom = top + 1;
                        int slabY = top + ROAD_LAYER;
                        for (int y = bottom; y < slabY; y++) {
                            world.setBlockState(new BlockPos(x, y, z), Blocks.LIGHT_GRAY_CONCRETE.getDefaultState(), 3);
                        }
                    }
                    case 4 -> { // 高架路面：底层车道（与地面同一水平面）+ 抬高桥面
                        setSurface(world, x, z, top, Blocks.BLACK_CONCRETE);
                        int yv = top + ROAD_LAYER;
                        world.setBlockState(new BlockPos(x, yv, z), Blocks.WHITE_CONCRETE.getDefaultState(), 3);
                    }
                    case 6 -> buildBuilding(world, x, z, top, Math.max(1, height));
                    default -> {
                    }
                }
            }
        }
        return true;
    }

    /** 地面元素直接替换世界地表块（如超平坦的草地），保证全图同一水平面。 */
    private static void setSurface(ServerWorld world, int x, int z, int top, Block block) {
        world.setBlockState(new BlockPos(x, top, z), block.getDefaultState(), 3);
    }

    private static void buildBuilding(ServerWorld world, int x, int z, int top, int height) {
        int base = top + 1; // 楼体从地面之上开始，紧贴统一地面层
        Block wall = height >= 10 ? Blocks.CYAN_TERRACOTTA
                : height >= 6 ? Blocks.LIGHT_GRAY_CONCRETE : Blocks.GRAY_CONCRETE;
        for (int y = base; y <= base + height; y++) {
            Block block;
            if (y == base + height) {
                block = Blocks.WHITE_CONCRETE; // 屋面
            } else if ((x + z + y) % 5 == 0) {
                block = Blocks.GLASS; // 窗点缀
            } else {
                block = wall;
            }
            world.setBlockState(new BlockPos(x, y, z), block.getDefaultState(), 3);
        }
    }

    /** 从紧凑 JSON 提取 int 数组：形如 "k":[1,2,3], "h":[4,0,...]。 */
    private static int[] parseArray(String text, String key) {
        int start = text.indexOf(key);
        if (start < 0) {
            return EMPTY;
        }
        int end = text.indexOf("]", start);
        if (end < 0) {
            return EMPTY;
        }
        String body = text.substring(start + key.length(), end).trim();
        if (body.isEmpty()) {
            return EMPTY;
        }
        String[] parts = body.split(",");
        List<Integer> values = new ArrayList<>(parts.length);
        for (String part : parts) {
            try {
                values.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                return EMPTY;
            }
        }
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }
}
