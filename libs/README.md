# libs/

放**可选集成**所需的编译期依赖（不会被提交到 git）。

目前需要：

- `DistantHorizons-*.jar` —— `compat/dh` 下的源码编译需要（DH API）。

构建：

```bash
./gradlew build
```

没有放 DH jar 时，`compat/dh` 里的类会编译失败；如果你不需要 DH 集成，
可以把 `src/main/java/com/sora/splineroads/compat/dh` 一并删除后再构建。
