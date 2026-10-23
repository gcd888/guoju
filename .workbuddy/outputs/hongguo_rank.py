#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
红果短剧热播榜提取脚本（免依赖，仅用标准库）

原理：
  页面为服务端渲染（React SSR），榜单数据直接写在 HTML 的 DOM 里。
  PC 端与移动端返回的 HTML 不完全一致（class 前缀 pc-*/mobile-* 且带随构建
  变化的 CSS-module 哈希后缀），但列表内容一致。
  因此本脚本不做 CSS 选择器匹配，只按"内容锚点"提取：
    - 列表项锚点:  h2 标题 / series_id 链接
    - 热度:        "8038万热度" 文本模式
    - 评分/收藏/点赞: "评分9.0" / "33.4万收藏" / "135.7万点赞"
    - 类型:        连续 <span> 标签段落
    - 简介:        较长的纯文本 <p> 段落
  这样无论 pc-* 还是 mobile-* 版式的 HTML 都能正确解析。

用法：
  python hongguo_rank.py                    # 输出 JSON 到 stdout
  python hongguo_rank.py --ua mobile        # 用手机 UA 抓取
  python hongguo_rank.py --out rank.json    # 保存 JSON
  python hongguo_rank.py --md rank.md       # 同时保存 Markdown
"""

import argparse
import html as htmllib
import json
import re
import sys
import urllib.request

URL = "https://hongguoduanju.com/rank/hot-drama"

UA_PC = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
         "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36")
UA_MOBILE = ("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
             "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1")

# ---------- 内容锚点正则（与 class 名无关） ----------

RE_TITLE = re.compile(r'<h2[^>]*>([^<>]+)</h2>')
RE_SID = re.compile(r'series_id=(\d+)')
RE_HEAT = re.compile(r'(\d+(?:\.\d+)?)\s*万?\s*热度')
RE_SCORE = re.compile(r'评分\s*([\d.]+)')
RE_FAV = re.compile(r'(\d+(?:\.\d+)?)\s*万?\s*收藏')
RE_LIKE = re.compile(r'(\d+(?:\.\d+)?)\s*万?\s*点赞')
# 类型标签段：取"包含>=2个短 span 的 <p> 块"（span 间可能夹有 svg 图标）
RE_P_BLOCK = re.compile(r'<p[^>]*>(.*?)</p>', re.S)
RE_SPAN = re.compile(r'<span[^>]*>([^<>]+)</span>')
# 简介：较长纯文本段落（不含标签）
RE_DESC = re.compile(r'<p[^>]*>([^<>]{40,})</p>')
RE_NEW = re.compile(r'>\s*新剧\s*<')


def fetch(url, ua):
    req = urllib.request.Request(url, headers={"User-Agent": ua})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read().decode("utf-8", errors="replace")


def strip_tags(fragment):
    """去掉 svg 等标签，仅保留文本。"""
    text = re.sub(r'<[^>]+>', '', fragment)
    return htmllib.unescape(text).strip()


def extract_items(page_html):
    """按内容锚点切分并提取每部剧的信息。"""
    # 找出所有剧名 h2 的位置作为条目边界（列表项之间天然以标题分隔）
    titles = [(m.start(), m.end(), m.group(1)) for m in RE_TITLE.finditer(page_html)]
    if not titles:
        return []

    items = []
    for i, (s, e, title) in enumerate(titles):
        # 条目区域：条目的其余数据（热度/互动/类型/简介）都渲染在剧名 h2 之后，
        # 因此取"本标题结束 -> 下一标题开始"，避免取到上一条的数据
        seg_start = e
        seg_end = titles[i + 1][0] if i + 1 < len(titles) else min(len(page_html), e + 8000)
        region = page_html[seg_start:seg_end]

        # series_id：标题的超链接 href 位于 h2 之前，向前回溯查找
        sid_m = RE_SID.search(page_html[max(0, s - 2000):e])
        item = {
            "rank": i + 1,
            "title": htmllib.unescape(title).strip(),
            "series_id": sid_m.group(1) if sid_m else None,
        }

        heat = RE_HEAT.search(region)
        item["heat"] = heat.group(1) + "万" if heat else None

        score = RE_SCORE.search(region)
        item["score"] = score.group(1) if score else None

        fav = RE_FAV.search(region)
        item["favorites"] = fav.group(1) + "万" if fav else None

        like = RE_LIKE.search(region)
        item["likes"] = like.group(1) + "万" if like else None

        item["is_new"] = bool(RE_NEW.search(region))

        item["categories"] = []
        for p_m in RE_P_BLOCK.finditer(region):
            spans = [htmllib.unescape(c).strip() for c in RE_SPAN.findall(p_m.group(1))]
            plain = strip_tags(p_m.group(1))
            # 类型段落特征：>=2 个 span 且整体文本很短（如"宫廷/古代/AI剧"）
            if len(spans) >= 2 and len(plain) <= 20:
                item["categories"] = spans
                break

        desc = RE_DESC.search(region)
        item["description"] = htmllib.unescape(desc.group(1)).strip() if desc else None

        items.append(item)
    return items


def to_markdown(items, updated=None):
    lines = ["# 红果短剧热播榜", ""]
    if updated:
        lines += [f"> 更新时间：{updated}", ""]
    lines += ["| 排名 | 剧名 | 类型 | 热度 | 收藏 | 点赞 | 评分 | 新剧 | 简介 |",
              "|---|---|---|---|---|---|---|---|---|"]
    for it in items:
        desc = (it["description"] or "")[:60] + ("…" if it["description"] and len(it["description"]) > 60 else "")
        lines.append("| {rank} | {title} | {cats} | {heat} | {fav} | {like} | {score} | {new} | {desc} |".format(
            rank=it["rank"], title=it["title"],
            cats="/".join(it["categories"]), heat=it["heat"] or "-",
            fav=it["favorites"] or "-", like=it["likes"] or "-",
            score=it["score"] or "-", new="是" if it["is_new"] else "",
            desc=desc.replace("|", "，")))
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser(description="提取红果短剧热播榜")
    ap.add_argument("--ua", choices=["pc", "mobile"], default="pc", help="User-Agent 类型")
    ap.add_argument("--out", help="JSON 输出路径")
    ap.add_argument("--md", help="Markdown 输出路径")
    args = ap.parse_args()

    ua = UA_MOBILE if args.ua == "mobile" else UA_PC
    page = fetch(URL, ua)

    m = re.search(r'"updatedText"\s*:\s*"([^"]*)"', page) or re.search(r'更新时间[^<>"]*', page)
    if m:
        raw = m.group(1) if m.lastindex else m.group(0)
        try:
            updated = json.loads('"' + raw + '"').strip()
        except Exception:
            updated = htmllib.unescape(raw).strip()
    else:
        updated = None

    items = extract_items(page)
    if not items:
        print("未解析到任何条目，页面结构可能已变化", file=sys.stderr)
        sys.exit(1)

    result = {"source": URL, "ua": args.ua, "updated": updated, "count": len(items), "items": items}

    out = json.dumps(result, ensure_ascii=False, indent=1)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(out)
    if args.md:
        with open(args.md, "w", encoding="utf-8") as f:
            f.write(to_markdown(items, updated))
    if not args.out and not args.md:
        print(out)


if __name__ == "__main__":
    main()
