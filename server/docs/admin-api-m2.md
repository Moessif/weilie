# 管理站 M2 API 契约（前端按此实现）

所有接口前缀 `/api/admin`，需管理站会话 Cookie `WEILIE_ADMIN`；写操作用 `POST/PATCH/DELETE` 时必须带 `X-CSRF-Token`。
分页响应统一 `{ ok, total, page, size, items }`；时间戳统一毫秒 epoch。

## 1. 房间

### GET /api/admin/rooms?status=&orgId=&q=&page=1&size=20

`status` 可选 `OPEN|IDLE|CLOSED`（不传=全部）；`q` 匹配 roomId / 房间名 / 邀请码。

```json
{
  "ok": true, "total": 32, "page": 1, "size": 20,
  "items": [{
    "roomId": "a6faea623f93", "name": "才吃饭", "joinCode": "384021",
    "orgId": 0, "orgName": "", "members": 0, "samples": 0,
    "markers": 0, "zones": 0, "photos": 0,
    "createdAt": 1794842216697, "lastActiveAt": 1794842216697,
    "status": "OPEN"
  }]
}
```

> `status` 口径：OPEN=有成员且 30 分钟内活跃；IDLE=无成员但 3 天内活跃；CLOSED=更久未活动。

### GET /api/admin/rooms/{roomId}

```json
{
  "ok": true,
  "room": { "...同列表项..." },
  "members": [{ "userId": "u1", "name": "王警官", "role": "CAPTAIN", "lat": 39.9, "lon": 116.4, "lastSeenAt": 1794842216697, "online": true }],
  "zones": [{ "id": "z1", "kind": "CIRCLE", "note": "自由绘制任务区", "assignees": ["u1"], "centerLat": 39.9, "centerLon": 116.4, "radiusM": 300 }],
  "markers": [{ "id": "m1", "name": "可疑摄像头", "byName": "王警官", "lat": 39.9, "lon": 116.4, "kind": "SUSPICIOUS", "ts": 1794842216697 }],
  "recentSamples": [{ "userId": "u1", "lat": 39.9, "lon": 116.4, "ts": 1794842216697, "targetSeen": true, "rsrp": -88 }]
}
```

### GET /api/admin/rooms/{roomId}/samples?limit=500

```json
{ "ok": true, "items": [{ "userId": "u1", "lat": 39.9, "lon": 116.4, "ts": 1794842216697, "targetSeen": true, "rsrp": -88 }] }
```

### GET /api/admin/rooms/{roomId}/export?format=csv|geojson|kml

返回文件下载（`Content-Disposition: attachment`）。

### POST /api/admin/rooms/{roomId}/close

`{ "ok": true }`；房间不存在返回 404 `ROOM_NOT_FOUND`。

### POST /api/admin/rooms/purge?days=3&emptyOnly=true

`{ "ok": true, "removed": 5 }`。`emptyOnly=true` 只清空房。

## 2. 取证照片

### GET /api/admin/photos?page=1&size=24&kind=&q=&onlyUploaded=false

`q` 匹配 code / sha256 前缀 / 设备 / 备注；`kind` 为 `PHOTO|SUSPICIOUS`（不传=全部）。

```json
{
  "ok": true, "total": 37, "page": 1, "size": 24,
  "items": [{
    "code": "SXB2CL8L", "sha256": "ab12...", "ts": 1794842216697,
    "lat": 39.9, "lon": 116.4, "device": "REDMI K80 Pro", "kind": "PHOTO",
    "note": "现场取证", "serverTs": 1794842216697,
    "hasFile": true, "sizeBytes": 303543
  }]
}
```

### GET /api/admin/photos/{code}/file

返回 `image/jpeg` 原图；无文件 404 `PHOTO_FILE_MISSING`。

## 3. 审计

### GET /api/admin/audit?page=1&size=30&level=&action=&q=&orgId=

```json
{ "ok": true, "total": 120, "page": 1, "size": 30,
  "items": [{ "id": 5, "ts": 1794842216697, "actorName": "超级管理员", "orgName": "中国人民公安大学·试点",
              "action": "client.activate", "detail": "device=dev1", "level": "success" }] }
```

`level` 为 `info|success|warning|danger`；`q` 匹配 actorName / action / detail。

### GET /api/admin/audit/export?format=csv

CSV 下载（UTF-8 BOM，列：时间/操作人/组织/动作/等级/详情）。

## 4. 运维

### GET /api/admin/ops/status

```json
{
  "ok": true,
  "server": { "version": "1.11.0", "uptimeSec": 3600, "startedAt": 1794838000000,
              "javaVersion": "17.0.2", "osName": "Linux", "cpuCores": 2,
              "heapUsedBytes": 134217728, "heapMaxBytes": 335544320, "threads": 64,
              "dataDir": "/opt/weilie/data", "dbBytes": 245760,
              "photosDirBytes": 10485760, "roomsDirBytes": 2097152,
              "diskFreeBytes": 40000000000 },
  "rooms": { "total": 32, "open": 0, "online": 0 },
  "metrics": { "wsConnections": 0, "messages": 120, "samples": 808, "photosUploaded": 37,
               "rateLimited": 0, "errors": 0, "exports": 3 },
  "limits": { "roomTtlDays": 3, "requireToken": false, "adminTokenConfigured": true }
}
```

### GET /api/admin/settings

```json
{ "ok": true, "roomTtlDays": 3, "requireToken": false, "adminTokenConfigured": true,
  "superadminUser": "admin", "version": "1.11.0", "schemaVersion": 1,
  "auditRetentionDays": 180, "activationCodeTtlDays": 7 }
```

### PATCH /api/admin/settings

请求体可含 `{ "roomTtlDays": 7 }`（范围 1..30）或 `{ "requireToken": true }`（仅记录期望值，实际生效需重启）。
响应同 `GET /api/admin/settings`。
