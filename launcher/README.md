# BREAKFRONT 专属启动器（.NET / Avalonia）

Geekhonize 账号 → 预写客户端会话 → 启动 BREAKFRONT 客户端。

## 工程结构
```
launcher/
  Geekhonize.Shared/   Auth REST 客户端 + GeoSession 会话文件(与 MC 客户端同格式) + LaunchPlan
  Geekhonize.App/      Avalonia UI（Win/Linux 桌面；代码式布局）
  .github/workflows/launcher.yml  三平台矩阵（Android 待 B1.5 多目标化）
```

## 构建
```
dotnet restore launcher/Geekhonize.App/Geekhonize.App.csproj
dotnet publish launcher/Geekhonize.App/Geekhonize.App.csproj -c Release -r linux-x64 --self-contained true
dotnet publish launcher/Geekhonize.App/Geekhonize.App.csproj -c Release -r win-x64  --self-contained true
```
产物无需安装 .NET 运行时。Android：B1.5 增加 `net8.0-android` 目标（Avalonia.Android）产出 APK。

## 登录链路（A1 已上线后端）
1. 启动器登录 → Auth `/login`（app=breakfront）→ 得 JWT + 用户信息。
2. 写入 `<实例>/config/breakfront-client.properties`（auth.token / auth.username，行级保留其它键）。
3. MC 客户端进服自动携带 token → 服务器 AuthBridge 校验 → 绑定账号防冒名（现有链路，零客户端改动）。

## B2（下一批）
实例/Java 自动寻址、Fabric 1.21.1 整合包下载与装配、自更新、直接启动按钮、Android APK。

- Launcher release assets（launcher-dev-<sha>）由 launcher.yml 发布，官网 /download 镜像同源。
- B2 引擎已并入 Shared（LaunchKit：Mojang+Fabric 装配/Java 探测/启动），Desktop 登录窗含「装配并启动」。
