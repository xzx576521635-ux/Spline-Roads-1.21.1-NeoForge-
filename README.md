# Spline Roads — 1.21.1 NeoForge port
所有代码均由AI制作完成
非官方 **Minecraft 1.21.1 / NeoForge** 移植版，基于 [ShiraAya/Spline-Roads-Release](https://github.com/ShiraAya/Spline-Roads-Release)
的 2.0 源码，并包含大量兼容性、渲染、道路写入与地图/DH 集成修复。

- 上游项目：<https://github.com/ShiraAya/Spline-Roads-Release>（1.20.1 Forge）
- 本仓库不是上游官方仓库；修改与问题请在本仓库反馈。
- 许可证与署名见 `LICENSE`、`THIRD_PARTY_NOTICES.md` 以及 `src/main/resources/META-INF/LICENSES`。

## 版本

当前源码对应 `splineroads-2.0-snowfix2.jar`（v56）：

- 标记方块按“坐标 + 类别 + 多道路归属”记账，删除道路不会误删邻路标记；
- 新建道路远端自动补放标记，Xaero 地图与 DH 远景可显示树木/路灯/绿化带/标线；
- 修复坡道人行道外侧面被裁剪导致的桥/坡侧看穿；
- 修复雪地/山区自动隧道道路无法删除（草方块 `snowy` 属性归一化导致校验失败）。

## 构建

环境要求：

- JDK 21
- Gradle 8.8+（仓库可通过 `gradle wrapper` 生成 wrapper）

步骤：

```bash
# 可选：把 Distant Horizons 等可选集成的 jar 放进 libs/
#   libs/DistantHorizons-...jar

./gradlew build          # Linux / macOS
gradlew.bat build        # Windows
```

产物：`build/libs/splineroads-<version>.jar`

详细说明（代理、发布到 fork、Releases）见 [`docs/构建与发布.md`](docs/构建与发布.md)。

> 没有 `libs/` 里的可选依赖时，与 Distant Horizons 相关的源码无法编译；
> 构建前请把对应 API jar 放入 `libs/`（该目录已被 `.gitignore` 排除）。

## 目录

```
src/main/java/       模组源码
src/main/resources/  assets / data / coremods / META-INF
docs/                开发记录与待办（变更记录、已知问题）
libs/                可选集成依赖（不提交）
```

## 许可证

- 本移植基于上游 2.0（MIT）修改；上游 2.1.0 起采用非商业许可，本仓库未包含 2.1.0 代码。
- 保留原作者署名；不得将本移植用于商业分发，除非获得原作者书面许可。
