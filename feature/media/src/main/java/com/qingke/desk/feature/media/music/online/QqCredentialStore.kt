package com.qingke.desk.feature.media.music.online

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 凭证落盘。
 *
 * ---------------------------------------------------------------------------
 * 三层，缺一层都不算"安全写入"
 *
 * 1. **位置**：应用私有目录 `filesDir`（Android 上 0700，非 root 设备其它应用读不到）。
 *    不写 `getExternalFilesDir` —— 那在 `/sdcard` 下，任何有存储权限的 App 都能读。
 * 2. **加密**：AES-256-GCM，密钥存在 **AndroidKeyStore** 里。
 *    关键点是**密钥本身不进文件系统**（由 TEE / StrongBox 保护），
 *    所以即使有人拿到了文件（adb backup、云备份、取证镜像），没有这台设备的 KeyStore 也解不开。
 * 3. **形状校验**：解密之后仍然要过一遍字段校验。**能解开不等于内容是合法的** ——
 *    GCM 保证完整性，但"我们自己写进去的时候就是坏的"（比如中途被改成空串）它管不了。
 *
 * ⚠️ **密钥不可用时拒绝登录并如实报错，不降级成明文。** 降级即红线失守，
 * 而这个降级太容易"顺手"加上去了 —— 一句 `catch { writePlain() }` 就够了。
 *
 * ---------------------------------------------------------------------------
 * 解不开的文件会被删掉
 *
 * 密钥消失只有几种原因（用户清了应用数据、KeyStore 被重置、系统升级异常），
 * 这几种情况下密文**永远解不开了**，留着它只会让每次启动都报一次错。
 * 删掉它并回到"未登录"，用户重扫一次即可 —— 这比让他卡在一个自己清不掉的状态里好。
 */
class QqCredentialStore(private val context: Context) {

    /**
     * 写入凭证。失败**会抛**（不是静默）—— 调用方必须知道"登录成功了但没存下来"，
     * 否则用户下次启动会莫名其妙地回到未登录。
     */
    fun save(cred: QqCredential) {
        val plain = JSONObject().apply {
            put("musicId", cred.musicId)
            put("musickey", cred.musickey)
            put("loginType", cred.loginType)
            put("nick", cred.nick)
            put("avatarUrl", cred.avatarUrl)
            put("createdAtMs", cred.createdAtMs)
            put("expiresAtMs", cred.expiresAtMs)
        }.toString().toByteArray(Charsets.UTF_8)

        val sealed = seal(plain)
        val file = File(context.filesDir, FILE_NAME)
        file.writeBytes(sealed)
        QqLog.i(TAG, "凭证已落盘（密文 ${sealed.size} 字节，${cred.mask()}）")
    }

    fun load(): QqCredential? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return null
        return try {
            val json = JSONObject(String(open(file.readBytes()), Charsets.UTF_8))
            val cred = QqCredential(
                musicId = json.optString("musicId").trim(),
                musickey = json.optString("musickey").trim(),
                loginType = json.optInt("loginType", LOGIN_TYPE_QQ_APP),
                nick = json.optString("nick"),
                avatarUrl = json.optString("avatarUrl"),
                createdAtMs = json.optLong("createdAtMs", 0L),
                expiresAtMs = json.optLong("expiresAtMs", 0L),
            )
            if (cred.musicId.isEmpty() || cred.musickey.isEmpty()) null else cred
        } catch (t: Throwable) {
            QqLog.w(TAG, "凭证解不开（${t.javaClass.simpleName}），按未登录处理并清除该文件")
            runCatching { file.delete() }
            null
        }
    }

    fun clear() {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
        // 密钥一并删掉：退出登录后不该留下一把"能解开旧密文"的钥匙。
        // 下一次 save() 会重新生成一把（代价只有一次密钥生成）。
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
        QqLog.i(TAG, "凭证与密钥已清除")
    }

    // ---- 加解密 --------------------------------------------------------------

    /** 密文布局：`[1 字节 iv 长度][iv][GCM 密文（含 16 字节 tag）]`。 */
    private fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    private fun open(blob: ByteArray): ByteArray {
        require(blob.size > 2) { "凭证文件损坏" }
        val ivLength = blob[0].toInt()
        require(ivLength in 1..32 && blob.size > 1 + ivLength) { "凭证文件损坏" }
        val iv = blob.copyOfRange(1, 1 + ivLength)
        val body = blob.copyOfRange(1 + ivLength, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(body)
    }

    /**
     * 取（或首次生成）KeyStore 里的 AES 密钥。
     *
     * 刻意**不设** `setUserAuthenticationRequired(true)`：那会要求设备近期解锁过才准用，
     * 在"熄屏常显的车机"场景下会把一台不常解锁的设备变成天天要重扫。
     * 我们要的是"密钥离开这台设备就无用"，而不是"每次用都要刷脸"。
     */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "QqCredStore"
        const val FILE_NAME = "qq-auth.bin"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.qingke.desk.qq.auth.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
