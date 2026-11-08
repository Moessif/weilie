# 百度地图 AK · 创建应用填写值记录

> 2026-11-06 从本机 keystore 提取。提交后「安全码」字段留空，系统自动生成 AK。

| 字段 | 值 |
|---|---|
| 应用类型 | Android SDK |
| PackageName | `com.gakj.weilie` |
| 开发版 SHA1 | `F628B2AD0FFCD0AC1CEB234FD0DA773BE14CC2C2` |
| 发布版 SHA1 | `D710C7BD9AA43A71446C2EE642D31734FC55A7C3` |

## keystore 位置

| 用途 | 路径 | 密码 | 别名 |
|---|---|---|---|
| debug（每日开发） | `%USERPROFILE%\.android\debug.keystore` | `android` | `androiddebugkey` |
| release（正式打包） | `%USERPROFILE%\.android\weilie-release.keystore` | `weilie2026` | `weilie` |

修改 release 密码：`keytool -storepasswd -keystore weilie-release.keystore -alias weilie`

## 验证指纹命令

```powershell
# debug
& "C:\AndroidDev\jdk-17.0.2\bin\keytool.exe" -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android

# release
& "C:\AndroidDev\jdk-17.0.2\bin\keytool.exe" -list -v -keystore "$env:USERPROFILE\.android\weilie-release.keystore" -alias weilie -storepass weilie2026
```
