# 签名密钥说明

`binf.keystore` 是这个 APK 的签名密钥，**必须长期保存**。

## 密钥不进版本库

`.gitignore` 排掉了 `signing/*.keystore`。

原因：密钥 + 口令一旦进公开仓库，任何人都能签出**同包名、同签名**的 APK，
Android 会把它当成正常更新装进已经装了本应用的手机里。这不是理论风险——
签名是 Android 判断"是不是同一个应用"的唯一依据。

所以密钥只留在本地。**自己备份好**（网盘、U 盘都行），丢了就只能换新密钥，
届时手机上必须先卸载旧版才能装新版，设置和数据会一起没。

## 参数

| 项 | 值 |
|---|---|
| 文件 | `signing/binf.keystore`（PKCS12） |
| alias | `binf` |
| 口令 | 默认 `binf123456`，可用环境变量 `BINF_KS_PASS` 覆盖 |
| 算法 | RSA 2048，有效期 10000 天 |
| 证书 | `CN=BinFloater, OU=Binf, O=Binf, L=CN, ST=CN, C=CN` |
| 证书 SHA-256 | `29698c03097636ce5cce86b394fef99fabf19933853a11ad231cacf3fc9a93c4` |

证书主题里没有任何个人信息（无姓名、无邮箱、无组织），可以安全公开。

## 验证签名

```bash
java -jar "$ANDROID_SDK/build-tools/36.0.0/lib/apksigner.jar" \
  verify --print-certs dist/BinFloater.apk
```

注意：`build-tools/36.0.0/apksigner` 这个无扩展名文件**不存在**，
要用 `java -jar .../lib/apksigner.jar` 或者 `.bat` 版本。

## 首次构建 / 换机器

`build.sh` 第 6 步会检查 `signing/binf.keystore` 是否存在：

- **存在** → 直接复用，构建出来的 APK 签名不变，手机可以覆盖安装
- **不存在** → 生成一个新密钥。此时签名和旧版不同，手机上装不上去，
  必须先卸载旧版

所以从别处 clone 仓库后，需要把自己的 `binf.keystore` 放进 `signing/`。

## 想自己发布的话

1. 用 `keytool -genkeypair` 生成自己的密钥，别用这个仓库里的默认值
2. 口令走环境变量 `BINF_KS_PASS`，不要写进脚本
3. 密钥文件放在仓库外，用软链或者 `--ks` 指过去
