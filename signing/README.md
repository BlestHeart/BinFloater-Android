# 签名密钥说明

`binf.keystore` 是这个 APK 的签名密钥，**必须长期保存**。

## 为什么放进版本库

Android 不允许用不同签名覆盖安装同一个包名的应用。密钥一丢，下次构建出来的
APK 签名就变了，手机上只能先卸载旧版（连带着设置和数据一起没）才能装新版。
所以它不能跟着 `build/` 一起被 `.gitignore` 排掉。

## 参数

| 项 | 值 |
|---|---|
| 文件 | `signing/binf.keystore`（PKCS12） |
| alias | `binf` |
| 口令 | `binf123456` |
| 算法 | RSA 2048，有效期 10000 天 |
| 证书 | `CN=BinFloater, OU=Binf, O=Binf, L=CN, ST=CN, C=CN` |
| 证书 SHA-256 | `29698c03097636ce5cce86b394fef99fabf19933853a11ad231cacf3fc9a93c4` |

## 验证签名

```bash
"/c/Program Files/Zulu/zulu-21/bin/java" \
  -jar /f/AndroidSdk/build-tools/36.0.0/lib/apksigner.jar \
  verify --print-certs dist/BinIME.apk
```

注意：`/f/AndroidSdk/build-tools/36.0.0/apksigner` 这个无扩展名文件**不存在**，
要用 `java -jar .../lib/apksigner.jar` 或者 `.bat` 版本。

## 换机器 / 重新 clone 后

`build.sh` 里的第 6 步会检查 `signing/binf.keystore` 是否存在，不存在才生成新的。
所以只要这个文件跟着仓库走，换机器构建出来的签名就是一致的。

## 安全说明

口令是明文写在 `build.sh` 里的。这是个**自用工具**，不上架任何应用商店，
密钥泄露的后果只是别人能签一个同包名的 APK —— 而安装它仍然需要用户手动确认。
真要发布的话，口令应该走环境变量，密钥本身也不该进仓库。
