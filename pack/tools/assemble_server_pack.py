"""组装 BREAKFRONT 服务端一键包（CI 专用）。
前置：CI 已用 fabric-installer 在 stage/server 生成 fabric-server-launch.jar + libs + server.jar。
本脚本：
1) 从客户端 dev zip 提取同一套第三方+自研 mods（保证双端一致）
2) 写入服务端模板配置（离线模式 / 接受转移 / 平坦竞技场 / 外部地图标记）
3) 产出 breakfront-dev-server-<sha>.zip

用法:
  python3 assemble_server_pack.py --sha <short> --client-zip <path> --stage <server dir> --out <dist>
"""
import argparse
import zipfile
import shutil
import pathlib
import json


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sha", required=True)
    ap.add_argument("--client-zip", required=True)
    ap.add_argument("--stage", required=True, help="fabric-installer 生成的服务端目录")
    ap.add_argument("--out", required=True, help="输出目录（dist）")
    args = ap.parse_args()

    stage = pathlib.Path(args.stage)
    dist = pathlib.Path(args.out)
    dist.mkdir(parents=True, exist_ok=True)

    # 1. mods：从客户端包提取 jar，但只放「双端可运行」(env==both) 的模组。
    #    客户端专属模组（entityculling / immediatelyfast / natural-motion-blur 及其
    #    cloth-config / satin 依赖、breakfront-client、sodium / iris / tacz）不进服务端，
    #    避免服务端误加载客户端渲染模组。env 取自客户端包内的 manifest.json。
    mods_dir = stage / "mods"
    mods_dir.mkdir(exist_ok=True)
    server_ok = set()
    with zipfile.ZipFile(args.client_zip) as z:
        names = set(z.namelist())
        if "manifest.json" in names:
            try:
                manifest = json.loads(z.read("manifest.json"))
                for entry in manifest.get("files", []):
                    if entry.get("env") == "both":
                        server_ok.add(entry.get("file"))
            except Exception as e:
                print(f"[warn] 解析 manifest.json 失败，回退为全量复制：{e}")
                server_ok = None
        else:
            print("[warn] 客户端包无 manifest.json，回退为全量复制")
            server_ok = None
        n = 0
        skipped = 0
        for name in z.namelist():
            if not (name.startswith("mods/") and name.endswith(".jar")):
                continue
            if server_ok is not None and name not in server_ok:
                skipped += 1
                continue
            (mods_dir / pathlib.Path(name).name).write_bytes(z.read(name))
            n += 1
    print(f"mods copied: {n} (服务端双端模组); skipped client-only: {skipped}")

    # 2. 模板配置
    (stage / "eula.txt").write_text("eula=true\n", encoding="utf8")
    (stage / "breakfront.map.external").write_text(
        "外部世界标记：放置服务器 world 目录后，模组将跳过自建城市。\n", encoding="utf8")
    props = "\n".join([
        "# BREAKFRONT dev server template (auto-generated)",
        "motd=BREAKFRONT dev server (1.21.1 Fabric)",
        "online-mode=false",
        "accepts-transfers=true",
        "level-type=minecraft\\:flat",
        "view-distance=12",
        "spawn-protection=0",
        "allow-flight=true",
        "max-players=64",
        "difficulty=peaceful",
        "pvp=true",
    ]) + "\n"
    (stage / "server.properties").write_text(props, encoding="utf8")
    (stage / "BREAKFRONT-SERVER.txt").write_text(
        "BREAKFRONT 服务端一键包\n"
        "用法：\n"
        "1) 将你的战场世界放进 world 目录（可选；不放则首启生成超平坦竞技场并跳过自建城市）\n"
        "2) 运行 start.bat（Windows）或 java -Xmx4G -jar fabric-server-launch.jar nogui\n"
        "3) 游戏内 /bf team attacker|defender 分组 → /bf start 开局\n"
        "模组更新源：http://<本机IP>:25610/breakfront/manifest.json（客户端 PLAY 前自动校验）\n",
        encoding="utf8")
    bat = (stage / "start.bat")
    bat.write_text("@echo off\r\njava -Xmx4G -jar fabric-server-launch.jar nogui\r\npause\r\n",
                   encoding="utf8")

    # 3. zip（含目录根 = 服务端目录内容）
    out_zip = dist / f"breakfront-dev-server-{args.sha}.zip"
    if out_zip.exists():
        out_zip.unlink()
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(stage.rglob("*")):
            if f.is_file():
                z.write(f, f.relative_to(stage))
    print(f"server zip: {out_zip} ({out_zip.stat().st_size / 1024 / 1024:.1f} MB)")


if __name__ == "__main__":
    main()
