# maps · 高架走廊（Viaduct）原型

本目录是 **首发图「高架走廊」** 的原型产物与生成工具链。
生成器位于 [`tools/citygen_viaduct_demo.py`](tools/citygen_viaduct_demo.py)，输出物位于 [`viaduct/proto/`](viaduct/proto/)。

## 原型长什么样

`viaduct/proto/preview_topdown.png` —— 顶视图（路网 + 楼体轮廓 + 高架横贯）

`viaduct/proto/preview_iso.png` —— 等距透视图（柱状挤出，便于看楼宇高低与高架纵深）

`viaduct/proto/layout.json` —— 机器可读布局：路网、楼宇足印与高度、广场、高架参数、生成种子

## 设计意图（对照 charter §7）

- **两纵一横**主干道（南北主道宽 8、东西主道宽 8）形成主轴，**中央交汇处下沉为广场**；
- **高架**横贯东西方向（y=8，距地面 7 格），立柱每 8 格一根——构成视觉地标与攻守分界线；
- 街块以 `BLOCK_SIDE=10` 自动划分，每个街块随机生成 1–3 栋楼宇（高度 4–13），边角撒掩体；
- 攻方推进方向沿 **x 轴自西向东**——便于后续接入扇区线（breakthrough sector 沿 +x 排列）。

## 如何重跑（受管 Python 环境）

```bash
# 一次性：创建受管 venv 并安装 Pillow
"C:/Users/huang/.workbuddy/binaries/python/versions/3.13.12/python.exe" \
    -m venv "C:/Users/huang/.workbuddy/binaries/python/envs/default"
"C:/Users/huang/.workbuddy/binaries/python/envs/default/Scripts/python.exe" \
    -m pip install pillow

# 生成（确定性：同 seed 永远同一张图）
"C:/Users/huang/.workbuddy/binaries/python/envs/default/Scripts/python.exe" \
    maps/tools/citygen_viaduct_demo.py --seed 42 --out maps/viaduct/proto
```

## 下一步路线

1. **NBT 导出** —— 把 `layout.json` 转成 Minecraft Structure Block 格式 `.nbt`，可直接放进服务器 world 用结构方块/WorldEdit 装载；
2. **WorldEdit 工作流** —— 创作服搭图并以 schem 导出，给生成器增加「导入 schem + 风格迁移」支持（可选扩展）；
3. **扇区标注** —— 把图分成 4 道防线，自动产出 charter §4.3 的 `breakfront:sectors` 数据包 JSON；
4. **手感验收** —— 你在游戏里实际跑一圈，把"哪里卡视野""哪条进攻轴太单一"的反馈回灌，参数化重跑。