# shizuku-api-13.1.5.jar

可选插件「Shizuku 特权」（默认关）用的 Shizuku 官方 API 库（MIT License，Copyright (c) 2021 RikkaW，https://github.com/RikkaApps/Shizuku-API；许可全文 `licenses/MIT-Shizuku-API.txt`。注意：Shizuku 服务端 App 本身是 Apache-2.0，客户端 API 库是 MIT）。
仓库是命令行构建（不用 Gradle），所以和 `zxing-core-3.5.3.jar` 一样以 jar 放在这里，由 `build_linux.sh` 编译 + 打进 dex，
`run_unit_tests.sh` 放进编译 classpath。

来源（Maven Central，版本 13.1.5）；把四个 aar 里的 `classes.jar` 原样解开、合并成一个 jar（类文件未改动，共 37 个）：

| aar | SHA-1（与 Maven Central 的 .sha1 一致） | SHA-256 |
|---|---|---|
| dev.rikka.shizuku:api:13.1.5 | 1f760c56493bdbc01462930e75bb325033b36fe2 | 4def9bde498ef8626614c2fc5db9af4749c86f16f6c33e3f5658d35e70bab59b |
| dev.rikka.shizuku:provider:13.1.5 | 4135b99cae9362fe2f63380230d04a9bcba9be3f | b0f18cd9812464ec171c53cac93a819fe411718a3965c311f01eb4de265381b3 |
| dev.rikka.shizuku:aidl:13.1.5 | 457504c277d796adea698b80b4e765f98dd0799a | 33fe7191cdd69fcb66d649264f3b0c47acb2f3d6343afc05b98dbbff6f221963 |
| dev.rikka.shizuku:shared:13.1.5 | 8c4e73ce466cd27aff10565d2ec49809c258f974 | 4659642c9339be0a26e9c65bb8648f7ad6d8f4a465f557993ccbc78802381635 |

aar 里的 Manifest 片段（`moe.shizuku.manager.permission.API_V23` 权限、`moe.shizuku.client.V3_SUPPORT` meta-data）和
`rikka.shizuku.ShizukuProvider` 声明已手工写进 `app/AndroidManifest.xml`（debug 包的 authority 由 `build_linux.sh` 改名）。
库对 `androidx.annotation` 的引用只是编译期注解，运行时不需要。
