# 取证照片在线验真接口

拍照落库时把照片 SHA-256 登记到服务器并取回 HMAC 签名；之后凭 8 位验证码 / 二维码
校验「这张照片确实是某时某地这台设备拍的、且没有被改过」。

数据文件（与现有数据同在 `WEILIE_DATA_DIR`，默认 `data/`）：

| 文件 | 说明 |
| --- | --- |
| `photo-registry.jsonl` | 追加式登记记录，启动时全量载入内存索引 |
| `hmac.secret` | 32 字节 HMAC 密钥（首次启动生成，600 权限；丢了旧签名全部作废） |

密钥来源优先级：环境变量 `WEILIE_HMAC_SECRET` > `数据目录/hmac.secret` >
随机生成并落盘。**生产环境建议显式设置 `WEILIE_HMAC_SECRET` 并妥善备份。**

签名原文（顺序不可改）：

```
code:sha256:serverTs:ts:lat:lon
```

签名算法：HMAC-SHA256，密钥如上，输出小写十六进制；校验一律用
`MessageDigest.isEqual`（常量时间）。

## 接口

### POST /api/photo/register

请求：

```json
{
  "sha256": "64 位十六进制",
  "ts": 1762500000000,
  "lat": 30.5,
  "lon": 114.3,
  "device": "deviceType",
  "kind": "scene",
  "note": "备注"
}
```

`sha256` 必须为 64 位十六进制，否则 400 `{"ok":false,"error":"sha256 必须为 64 位十六进制"}`。

响应：

```json
{ "ok": true, "code": "T8QVW43T", "sig": "<64 位 hex>", "serverTs": 1762500000123 }
```

`code` 为 8 位大写字母数字（去除 I/O/0/1，避免人工抄错），全局唯一、冲突重试。

### GET /api/photo/verify?code=CODE

按验证码查登记记录（不比对照片内容）：

```json
{
  "ok": true,
  "found": true,
  "hashMatch": null,
  "sigValid": true,
  "record": {
    "code": "T8QVW43T",
    "sha256": "…",
    "ts": 1762500000000,
    "lat": 30.5,
    "lon": 114.3,
    "device": "deviceType",
    "kind": "scene",
    "note": "备注",
    "serverTs": 1762500000123,
    "sig": "…"
  }
}
```

未命中：`{"ok":true,"found":false,"hashMatch":null,"sigValid":null,"record":null}`。

### POST /api/photo/verify

一次完成「验证码 + 当前照片哈希」比对：

```json
{ "code": "T8QVW43T", "sha256": "<64 位 hex>" }
```

响应在 GET 的基础上给出 `hashMatch`：

```json
{
  "ok": true,
  "found": true,
  "hashMatch": true,
  "sigValid": true,
  "record": { "...": "同上" }
}
```

`hashMatch=false` 表示照片内容与登记时不一致（被改过 / 拍错照片）。

### GET /api/photo/count

```json
{ "ok": true, "count": 2 }
```

## curl 自测

```bash
# 登记
curl -s -X POST http://127.0.0.1:8080/api/photo/register \
  -H 'Content-Type: application/json' \
  -d '{"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","ts":1762500000000,"lat":30.5,"lon":114.3,"device":"dev","kind":"scene","note":"unit"}'

# 校验
curl -s -X POST http://127.0.0.1:8080/api/photo/verify \
  -H 'Content-Type: application/json' \
  -d '{"code":"<上一步的 code>","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}'

# 按码查询
curl -s 'http://127.0.0.1:8080/api/photo/verify?code=<code>'
```

## 客户端约定

- App 回执存 `filesDir/photos/verify.jsonl`，字段
  `{"photoId","code","sig","sha256","serverTs"}`，不回写 `PhotoRecord`（避免污染旧索引）。
- 拍照后自动登记；失败留在待登记队列，打开照片详情页时自动补登记重试。
- 详情页展示 8 位验证码与 `WEILIE-VERIFY:<code>` 二维码，可在线验证：
  **登记记录**（服务器时间）/ **内容一致性**（hashMatch）/ **签名校验**（sigValid）。
