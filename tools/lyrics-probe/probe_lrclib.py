#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
LRCLIB 中文曲目命中率实测 —— DeskTerminal 音乐模块歌词来源评估（阶段一 第 4 项）

目的：在接入前用真实数据回答三个问题
  1. 行级歌词（plain / synced LRC）的中文曲目覆盖率是多少？
  2. 逐字歌词（enhanced LRC，词内 <mm:ss.xx> 内联时间戳）覆盖率是多少？
  3. 三级回退（精确 get -> 松散 get -> search top1）各自贡献多少命中？

三级回退（与客户端实现保持一致）：
  L1  GET /api/get      带 track_name + artist_name + album_name + duration（精确）
  L2  GET /api/get      只带 track_name + artist_name（专辑/时长可能因发行版不同而不匹配）
  L3  GET /api/search   track_name + artist_name，取 top1，同时记录候选条数

命中判据（刻意分开，避免"能返回 JSON 就算命中"的假阳性）：
  - synced：syncedLyrics 非空且能解析出时间戳行 —— 才允许做逐行滚动与拖动
  - word  ：syncedLyrics 里出现 <mm:ss.xx> 内联标签 —— 才允许做逐字高亮
  - plain ：仅 plainLyrics 非空 —— 只能做静态展示，不能滚动同步

Usage:
  python probe_lrclib.py            # 跑内置 20 首，打印表格 + 汇总
  python probe_lrclib.py out.json   # 额外落一份原始结果
"""

import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

UA = "DeskTerminal/0.1.0"          # 用户要求：User-Agent 写应用名
BASE = "https://lrclib.net/api"
SLEEP_BETWEEN = 0.4                # 礼貌节流，避免给公共实例压力

# 行级时间戳 [mm:ss.xx] / [mm:ss.xxx] / [mm:ss:xx]
RE_LINE_TS = re.compile(r"\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?\]")
# 逐字（enhanced LRC）内联时间戳 <mm:ss.xx>
RE_WORD_TS = re.compile(r"<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>")

TRACKS = [
    {"title": "不遗憾", "artist": "李荣浩", "album": "麻雀", "duration": 324, "note": "参考图同款曲目 / VIP"},
    {"title": "晴天", "artist": "周杰伦", "album": "叶惠美", "duration": 269, "note": "周杰伦 / VIP"},
    {"title": "七里香", "artist": "周杰伦", "album": "七里香", "duration": 299, "note": "周杰伦 / VIP"},
    {"title": "夜曲", "artist": "周杰伦", "album": "11月的萧邦", "duration": 226, "note": "周杰伦 / VIP"},
    {"title": "一路向北", "artist": "周杰伦", "album": "J III MP3 Player", "duration": 295, "note": "周杰伦 / VIP"},
    {"title": "告白气球", "artist": "周杰伦", "album": "周杰伦的床边故事", "duration": 215, "note": "周杰伦 / VIP"},
    {"title": "富士山下", "artist": "陈奕迅", "album": "What's Going On...?", "duration": 259, "note": "粤语经典 / VIP"},
    {"title": "孤勇者", "artist": "陈奕迅", "album": "孤勇者", "duration": 256, "note": "华语热门"},
    {"title": "年少有为", "artist": "李荣浩", "album": "耳朵", "duration": 279, "note": "李荣浩 / VIP"},
    {"title": "乌梅子酱", "artist": "李荣浩", "album": "纵横四海", "duration": 246, "note": "李荣浩"},
    {"title": "修炼爱情", "artist": "林俊杰", "album": "因你 而在", "duration": 287, "note": "林俊杰 / VIP"},
    {"title": "江南", "artist": "林俊杰", "album": "第二天堂", "duration": 268, "note": "林俊杰"},
    {"title": "泡沫", "artist": "G.E.M. 邓紫棋", "album": "Xposed", "duration": 258, "note": "邓紫棋 / VIP"},
    {"title": "光年之外", "artist": "G.E.M. 邓紫棋", "album": "光年之外", "duration": 235, "note": "邓紫棋"},
    {"title": "起风了", "artist": "买辣椒也用券", "album": "起风了", "duration": 325, "note": "多版本同名测试"},
    {"title": "成都", "artist": "赵雷", "album": "无法长大", "duration": 328, "note": "民谣独立"},
    {"title": "杀死那个石家庄人", "artist": "万能青年旅店", "album": "万能青年旅店", "duration": 344, "note": "独立摇滚"},
    {"title": "悬溺", "artist": "葛东琪", "album": "第二街区", "duration": 197, "note": "流媒体热歌"},
    {"title": "Blinding Lights", "artist": "The Weeknd", "album": "After Hours", "duration": 200, "note": "英文热单对照"},
    {"title": "Cruel Summer", "artist": "Taylor Swift", "album": "Lover", "duration": 178, "note": "英文热单对照"},
]


def http_json(url):
    """返回 (status, payload)；payload 为 dict/list 或 None。"""
    req = urllib.request.Request(
        url, headers={"User-Agent": UA, "Accept": "application/json"}
    )
    try:
        with urllib.request.urlopen(req, timeout=25) as resp:
            body = resp.read().decode("utf-8", "replace")
            return resp.status, json.loads(body)
    except urllib.error.HTTPError as e:
        return e.code, None
    except Exception as e:  # 网络/解析异常一律记为 -1，不中断整轮
        return -1, str(e)


def api_get(track, artist, album=None, duration=None):
    q = {"track_name": track, "artist_name": artist}
    if album:
        q["album_name"] = album
    if duration:
        q["duration"] = str(duration)
    return http_json(f"{BASE}/get?{urllib.parse.urlencode(q)}")


def api_search(track, artist):
    q = {"track_name": track, "artist_name": artist}
    return http_json(f"{BASE}/search?{urllib.parse.urlencode(q)}")


def analyze(entry):
    """把一条 LRCLIB 记录压成判据集合。"""
    if not isinstance(entry, dict):
        return None
    synced = (entry.get("syncedLyrics") or "").strip()
    plain = (entry.get("plainLyrics") or "").strip()
    line_ts = RE_LINE_TS.findall(synced)
    word_ts = RE_WORD_TS.findall(synced)
    return {
        "has_synced": len(line_ts) > 0,
        "has_word": len(word_ts) > 0,
        "has_plain": len(plain) > 0,
        "synced_lines": len(line_ts),
        "word_tags": len(word_ts),
        "instrumental": bool(entry.get("instrumental")),
        "matched_track": entry.get("trackName"),
        "matched_artist": entry.get("artistName"),
        "matched_album": entry.get("albumName"),
        "matched_duration": entry.get("duration"),
        "lrclib_id": entry.get("id"),
    }


def probe(t):
    """对单首走三级回退，返回结果 dict。"""
    res = {
        "title": t["title"], "artist": t["artist"], "album": t["album"],
        "duration": t["duration"], "note": t["note"],
        "stage": None, "candidates": None, "info": None, "detail": [],
    }

    # L1 精确
    st, payload = api_get(t["title"], t["artist"], t["album"], t["duration"])
    res["detail"].append({"stage": "L1 get(全参)", "status": st})
    info = analyze(payload) if st == 200 else None
    if info:
        res.update(stage="L1", info=info)
        return res

    time.sleep(SLEEP_BETWEEN)

    # L2 松散
    st, payload = api_get(t["title"], t["artist"])
    res["detail"].append({"stage": "L2 get(名+歌手)", "status": st})
    info = analyze(payload) if st == 200 else None
    if info:
        res.update(stage="L2", info=info)
        return res

    time.sleep(SLEEP_BETWEEN)

    # L3 搜索 top1
    st, payload = api_search(t["title"], t["artist"])
    n = len(payload) if isinstance(payload, list) else None
    res["detail"].append({"stage": "L3 search", "status": st, "n": n})
    if isinstance(payload, list) and payload:
        info = analyze(payload[0])
        res.update(stage="L3", candidates=n, info=info)
        return res

    res.update(stage="MISS")
    return res


def main():
    rows = []
    for i, t in enumerate(TRACKS, 1):
        r = probe(t)
        rows.append(r)
        info = r["info"] or {}
        tag = r["stage"]
        flags = (
            ("S" if info.get("has_synced") else "-")
            + ("W" if info.get("has_word") else "-")
            + ("P" if info.get("has_plain") else "-")
        )
        print(
            f"[{i:2d}/20] {tag:<4} {flags:<3} "
            f"{t['title']} / {t['artist']}  "
            f"(synced行={info.get('synced_lines', 0)}, 词标签={info.get('word_tags', 0)}, "
            f"候选={r['candidates']})",
            flush=True,
        )
        time.sleep(SLEEP_BETWEEN)

    # ---------------- 汇总 ----------------
    total = len(rows)
    hit = [r for r in rows if r["info"]]
    synced = [r for r in rows if (r["info"] or {}).get("has_synced")]
    word = [r for r in rows if (r["info"] or {}).get("has_word")]
    plain_only = [
        r for r in rows
        if (r["info"] or {}).get("has_plain") and not (r["info"] or {}).get("has_synced")
    ]

    def pct(a, b=total):
        return f"{a}/{b} ({100.0 * a / b:.1f}%)"

    print("\n" + "=" * 72)
    print("LRCLIB 命中率汇总（DeskTerminal/0.1.0）")
    print("=" * 72)
    print(f"任意命中（含仅 plain）      : {pct(len(hit))}")
    print(f"行级 synced（可滚动/可拖动）: {pct(len(synced))}")
    print(f"逐字 word-level             : {pct(len(word))}")
    print(f"仅 plain（仅静态展示）      : {pct(len(plain_only))}")
    print(f"完全未命中                  : {pct(total - len(hit))}")
    for stage in ("L1", "L2", "L3", "MISS"):
        c = sum(1 for r in rows if r["stage"] == stage)
        print(f"  命中来源 {stage:<4}: {c}")
    print("-" * 72)

    zh = [r for r in rows if not _is_ascii(r["title"])]
    zh_synced = [r for r in zh if (r["info"] or {}).get("has_synced")]
    zh_word = [r for r in zh if (r["info"] or {}).get("has_word")]
    en = [r for r in rows if _is_ascii(r["title"])]
    en_synced = [r for r in en if (r["info"] or {}).get("has_synced")]
    en_word = [r for r in en if (r["info"] or {}).get("has_word")]
    print(f"中文曲目 {len(zh)} 首  -> 行级 {pct(len(zh_synced), len(zh))} | 逐字 {pct(len(zh_word), len(zh))}")
    print(f"英文曲目 {len(en)} 首  -> 行级 {pct(len(en_synced), len(en))} | 逐字 {pct(len(en_word), len(en))}")

    if len(sys.argv) > 1:
        with open(sys.argv[1], "w", encoding="utf-8") as f:
            json.dump(
                {
                    "user_agent": UA,
                    "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                    "rows": rows,
                },
                f, ensure_ascii=False, indent=2,
            )
        print(f"\n原始结果已写入 {sys.argv[1]}")


def _is_ascii(s):
    try:
        s.encode("ascii")
        return True
    except UnicodeEncodeError:
        return False


if __name__ == "__main__":
    main()
