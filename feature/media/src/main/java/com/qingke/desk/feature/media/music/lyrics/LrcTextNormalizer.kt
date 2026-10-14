package com.qingke.desk.feature.media.music.lyrics

/**
 * 把 LRC 的时间戳统一成 `[mm:ss.xx]` 这一种写法。
 *
 * ---------------------------------------------------------------------------
 * 为什么需要它（不是"锦上添花"，是**必须**）
 *
 * 解析库 `accompanist-lyrics-core` 的格式判定正则是
 * `\[\d{2}:\d{2}\.\d{2,3}\]` —— **只认 `[mm:ss.xx]`**。别的写法一律判成
 * "这个文件我不认识"，`AutoParser` 于是返回**空**（不报错、不抛异常）。
 *
 * 实测这条正则挡住的常见写法（都是真实播放器导出的格式）：
 *
 * | 写法 | 例子 | 含义 |
 * |---|---|---|
 * | 无小数 | `[00:12]` | 整秒 |
 * | 冒号当百分秒 | `[00:12:34]` | 12 秒 340 毫秒 |
 * | 1 位分钟 | `[0:12.34]` | 0 分 12.34 秒 |
 * | 1 位小数 | `[00:12.3]` | 12.3 秒 |
 *
 * 也就是说：**不做归一化，本地导入会有一大半文件"导入成功但歌词是空的"** ——
 * 这正是本项目反复强调的那类故障：「它也在工作，但工作得不对」。
 *
 * ---------------------------------------------------------------------------
 * 边界（改这个正则前先读）
 *
 * · **只改时间戳，不碰元数据标签**。判据是"冒号前必须是数字"：
 *   `[ti:晴天]`、`[ar:李荣浩]`、`[offset:0]`、`[al:麻雀]` 里的冒号前都是字母，
 *   一个都不会被匹配到。
 * · 三字段 `[a:b:c]` 一律按 **分:秒:百分秒** 解释（LRC 的行时间戳里没有
 *   `时:分:秒` 这种用法，那是 .srt 的习惯）。所以 `[00:12:34]` → `[00:12.34]`。
 * · 分钟超过 99 的文件（100 分钟以上的单曲）**归一化不了** —— 库的正则要求
 *   恰好两位分钟。这种文件极少，这里不为了它去写一个"两位以上就不改"的特例，
 *   免得引入更隐蔽的错。真遇到了会在页面上表现为"没有同步歌词"。
 */
object LrcTextNormalizer {

    /**
     * 方括号与尖括号两种时间戳一起改：
     *   · `[00:12.34]text`  —— 标准 LRC 的行时间戳（也是"劣质逐字"格式里音节的写法）
     *   · `<00:12.34>text`  —— Enhanced LRC 的音节时间戳
     * 两种都要能进库的正则，否则 Enhanced LRC 的 `canParse` 照样是 false。
     */
    private val TIMESTAMP = Regex("""([<\[])(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?([>\]])""")

    /** 归一化整份文本。幂等：归一化过的内容再跑一次结果不变。 */
    fun normalize(text: String): String = TIMESTAMP.replace(text) { m ->
        val open = m.groupValues[1]
        val minutes = m.groupValues[2]
        val seconds = m.groupValues[3]
        val fraction = m.groupValues[4]
        val close = m.groupValues[5]

        val mm = minutes.padStart(2, '0')
        val ss = seconds.padStart(2, '0')
        val frac = when (fraction.length) {
            0 -> "00"
            1 -> fraction + "0"
            2 -> fraction
            else -> fraction.take(3)
        }
        "$open$mm:$ss.$frac$close"
    }

    /**
     * 归一化后是否"看起来有同步时间轴"。
     *
     * 这是**外部判据**，与库的 `canParse` 无关：库只判断"我认不认这个格式"，
     * 而我们要判断的是"这台机器上最终能不能拿到行级时间轴"。
     * 用它做拉取层的命中判据，才不会出现"HTTP 200 但内容是空的"被当成命中。
     */
    fun hasTimestamps(text: String): Boolean = TIMESTAMP.containsMatchIn(text)
}
