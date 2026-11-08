# 围猎 · 后端服务（weilie-server）

「围猎」多人协同后端：民警在野外实测目标基站（ECI/NCI）真实覆盖范围时，负责队伍创建、房间内成员位置/采样/聊天/标记/任务范围的实时协同。内存态 + JSON 快照持久化，无数据库，单机 Docker 一键部署。

## 技术栈

| 项 | 选型 |
|---|---|
| 语言 / JDK | Kotlin 2.0.21 / JDK 17 |
| 服务框架 | Ktor 2.3.12（Netty 引擎） |
| 序列化 | kotlinx.serialization 1.7.3（与 Android 客户端同版本，协议共用字段名） |
| 日志 | logback 1.5.12 |
| 持久化 | 内存 Map + 每 30 秒 JSON 快照（`data/snapshot.json`），无数据库 |
| 构建 | Gradle 8.9 Kotlin DSL，依赖走阿里云镜像 |

## 目录结构

```
server/
├── settings.gradle.kts            # 仓库配置（阿里云镜像优先）
├── build.gradle.kts               # 依赖与 fatJar 任务
├── gradle.properties
├── gradle/wrapper/                # 仅 wrapper.properties（本机用 gradle.bat，不生成 wrapper jar）
├── Dockerfile                     # gradle 构建 -> temurin 17-jre 运行
├── docker-compose.yml             # 一键部署（8080 端口 + ./data 卷）
└── src/main/
    ├── kotlin/com/gakj/weilie/server/
    │   ├── Protocol.kt            # 与 Android 客户端逐字段对齐的消息定义
    │   ├── WsJson.kt              # 共享 Json 配置（ignoreUnknownKeys + encodeDefaults）
    │   ├── RoomStore.kt           # 房间仓库：roomId/joinCode 双索引 + 快照持久化
    │   ├── TeamRoutes.kt          # REST：建队/查码/健康检查
    │   ├── RoomWs.kt              # WS：房间消息处理与广播
    │   └── Application.kt         # 入口：8080 端口、路由装配、30s 快照调度
    └── resources/logback.xml      # 控制台日志（UTF-8）
```

## 本机构建与运行（Windows）

```bat
:: 编译（要求 JAVA_HOME 指向 JDK17）
set JAVA_HOME=C:\AndroidDev\jdk-17.0.2&& C:\AndroidDev\gradle-8.9\bin\gradle.bat -p <server目录> build --console=plain

:: 直接运行
C:\AndroidDev\jdk-17.0.2\bin\java -jar build\libs\weilie-server-all.jar
```

启动后监听 `0.0.0.0:8080`，快照写在进程工作目录的 `data/snapshot.json`（可用环境变量 `WEILIE_DATA_DIR` 覆盖）。

## REST 接口

| 方法 | 路径 | 说明 | 响应 |
|---|---|---|---|
| POST | `/api/teams` | 创建队伍，body：`{"name":"一队"}` | `{"roomId":"...","joinCode":"013579"}` |
| GET | `/api/teams/by-code/{code}` | 按 6 位邀请码查房间 | 命中 `{"roomId":"...","joinCode":"013579"}`，未命中 404 |
| GET | `/api/health` | 健康检查 | `{"ok":true}` |
| GET | `/` | 状态页 | 房间数 / 在线人数 / 运行时长 |

## WebSocket 协议（/ws/room/{roomId}）

连接：`ws://host:8080/ws/room/{roomId}?userId=&name=&role=`（role：CAPTAIN / MEMBER / OBSERVER）。
传输层为 JSON 文本帧，判别字段 `"type"`；连接后客户端应立即发送 `hello` 完成注册。

### 消息类型与服务端处理

| type | 方向 | 服务端处理 |
|---|---|---|
| `hello` | C→S | 记录成员与会话（同 userId 顶号时关闭旧连接，保留历史位置与采样计数），向**全员**广播 `state` |
| `loc` | C→S | 更新该成员 `lat/lon/acc/onTarget/lastTs`，**原样转发给其他成员** |
| `sample` | C→S | 该成员 `sampleCount+1` 并更新位置，原样转发**全员（含发送者）** |
| `chat` | C→S | 包装为 `chatEcho` 广播**全员（含发送者）** |
| `photo` | C→S | 向发送者回 `photoAck{ok:true}`，原样转发给其他成员（照片本体走云上传，房间内只传引用） |
| `marker` | C→S | 追加进房间标记列表并原样广播；标记会随 `state.markers` 下发 |
| `zone` | C→S | **仅 CAPTAIN 可下发**：记录并广播 `state`；非队长忽略并记日志 |
| `ping` | C→S | 回 `pong`（原样回传 `ts`） |
| `state` | S→C | 房间全量视图：`members`（含每人位置/采样计数）/ `zone` / `markers`；在 hello、marker、zone、成员离线时触发 |
| `chatEcho` | S→C | `{"chat":{...}}` 包装 |
| `photoAck` | S→C | 照片回执 |
| `bye` | S→C | 成员离线（reason=disconnect）/ 房间不存在 / 顶号通知 |
| `pong` | S→C | 心跳应答 |

约定：

- 未知 `type` 或解析失败的帧一律忽略（前向兼容，客户端可先行加字段）；
- 服务端另启协议层保活 ping（15s 间隔 / 60s 超时），快速识别移动网络半开连接；
- 断开连接即移除成员并向剩余成员广播 `bye` + `state`。

## 快照持久化

- 每 30 秒将全部房间（roomId、邀请码、zone、markers）写入 `data/snapshot.json`：先写 `.tmp` 再原子替换；
- 启动时自动恢复；快照损坏则空库启动，不影响服务可用性；
- 成员列表与 WS 会话属瞬态，不持久化——客户端有自动重连 + 重发 hello 逻辑，恢复后自然重建。

## Docker 部署（本机/阿里云通用）

```bash
# 构建并启动（在 server 目录下）
docker compose up -d --build

# 验证
curl http://127.0.0.1:8080/api/health     # {"ok":true}

# 查看日志
docker logs -f weilie-server
```

镜像分两段：`gradle:8.9-jdk17` 构建 fat jar（依赖走阿里云镜像），`eclipse-temurin:17-jre` 运行；快照持久化到宿主机 `./data`。

## 阿里云 ECS 部署步骤

1. **安全组**：ECS 控制台为实例安全组放行 TCP `8080`（或用 Nginx 反代 80→8080）；
2. **安装 Docker**：`curl -fsSL https://get.docker.com | bash && systemctl enable --now docker`（国内服务器建议再配镜像加速器）；
3. **上传代码**：`scp -r server/ root@<ECS公网IP>:/opt/weilie/`（或 git clone 整个 weilie 仓库）；
4. **启动**：`cd /opt/weilie/server && docker compose up -d --build`；
5. **验证**：`curl http://127.0.0.1:8080/api/health`，外网浏览器访问 `http://<ECS公网IP>:8080/` 看状态页；
6. **客户端对接**：App 内后端地址填 `ws://<ECS公网IP>:8080`（REST 为 `http://<ECS公网IP>:8080`）；
7. **运维**：`docker compose down` 停止（`./data` 卷保留）；`docker compose up -d --build` 升级重启后邀请码与房间状态自动恢复。

> 提示：正式比赛/演示建议绑域名并上 TLS（ws→wss），App 端明文 HTTP 需在网络安全配置中放行对应域。
