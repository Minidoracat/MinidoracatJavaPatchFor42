#!/usr/bin/env python3
"""把 docs/report/2026-09-02-tis-reports-{A,B,C}.md 的每份 `### Body` fenced block 轉成論壇可貼的 HTML。

用法：python scripts/tis_forum_html.py  → docs/report/forum-html/<A-R1|B-R3|C1…>.html

貼法：用瀏覽器（Chrome/Edge）開該 .html → 從 Version: 那行開始拖選到最後 → Ctrl+C → 論壇編輯器
Ctrl+V（富文本）。段落／粗體小標／code block／清單／表格都會保留（A-R1 已實貼驗證）。
**不要**用編輯器開 .html 複製（會貼到原始碼），也**不要** Ctrl+Shift+V（純文字會失去所有格式）。

轉換規則（草稿是 80 字硬換行的純文字）：
- 空行分段；連續非縮排行合併成一段。
- 五行欄位頭（Version/Mode/Server settings/Mods/Save/Crash）各自獨立一段。
- 行後緊接 `-----` 底線 → 該行是小標（<strong>），底線丟棄。
- `- ` / `N. ` 開頭 → 清單；其後的縮排續行／縮排段落／縮排 code 都附在同一項（空行不切斷清單）。
- 縮排區塊：縮排 ≥5 空格，或內容像程式碼／堆疊（行首 `at `、含 `{` `}` `;` `//` `->` `==` `()`）→ <pre>；
  否則視為段落。
- `| a | b |` markdown 表格 → <table>。
- `**x**` → <strong>；`` `x` `` → <code>。
- Body 的文字本身不動（不改字、不加字）。
"""
import html
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = {
    "A": ROOT / "docs/report/2026-09-02-tis-reports-A.md",
    "B": ROOT / "docs/report/2026-09-02-tis-reports-B.md",
    "C": ROOT / "docs/report/2026-09-02-tis-reports-C.md",
}
OUT = ROOT / "docs/report/forum-html"

FIELD_RE = re.compile(r"^(Version|Mode|Server settings|Mods|Save|Crash):\s")
LIST_RE = re.compile(r"^(\s*)(-|\*|\d+\.)\s+(.*)$")
UNDERLINE_RE = re.compile(r"^(-{3,}|={3,})$")
CODEISH_RE = re.compile(r"(^\s*at\s|[{};]\s*$|^\s*[{}]|\s//\s|^\s*\.\.\.)")
STACK_RE = re.compile(r"^\s*at\s+[\w.$]+\(|^\s*[a-z]+\.[\w.$]*(Exception|Error)\b")


def inline(s: str) -> str:
    """散文的行內格式。刻意**不產生** <code>／<em>：論壇編輯器（IPS 5 / CKEditor）貼上 HTML 時，
    6 篇裡 4 篇把行內 <code> 全數搬到段尾（A-R2/A-R3/A-R4/A-R6 實貼對帳），且 `==x==` 會被當
    highlight 語法吃掉 `==`。只留 <strong>（6/6 存活）。"""
    s = re.sub(r"`([^`]+)`", r"\1", s)
    s = re.sub(r"(?<![*\w])\*(?!\s)([^*]+?)\*(?![*\w])", r"\1", s)
    s = s.replace(" == ", " is ")
    s = html.escape(s, quote=False)
    s = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", s)
    return s


PASTE_RE = re.compile(r'^<paste "## (.+?)" … "## (.+?)" from (\S+) here[^>]*>$', re.M)


def markdown_to_draft(md: str) -> str:
    """把 markdown 章節轉成本轉換器吃的草稿方言：`## X` → 底線小標、fenced code → 縮排 5 格、`---` 丟棄。"""
    out: list[str] = []
    in_fence = False
    for line in md.split("\n"):
        if line.startswith("```"):
            in_fence = not in_fence
            out.append("")
            continue
        if in_fence:
            out.append("     " + (line if line.strip() else "\u00a0"))
            continue
        if line.strip() == "---":
            continue
        m = re.match(r"^#{2,}\s+(.*)$", line)
        if m:
            out += ["", m.group(1), "-----"]
            continue
        out.append(line)
    return "\n".join(out)


def expand_paste(body: str) -> str:
    def repl(m):
        first, last, rel = m.group(1), m.group(2), m.group(3)
        md = (ROOT / rel).read_text(encoding="utf-8")
        a = md.index("\n## " + first)
        b = md.index("\n## " + last)
        nxt = md.find("\n## ", b + 1)
        section = md[a + 1: nxt if nxt != -1 else len(md)]
        return markdown_to_draft(section)
    return PASTE_RE.sub(repl, body)


def indent_of(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def codeish(block: list[str]) -> bool:
    if not block:
        return False
    if min(indent_of(l) for l in block) >= 5:
        return True
    if any(STACK_RE.search(l) for l in block):
        return True
    # 對齊欄位表（行內 3+ 連續空格）或 →/-> 呼叫鏈：合併成段落會毀掉排版，當 <pre>
    if len(block) >= 2 and any(re.search(r"\S {3,}\S", l) or re.match(r"\s*(→|->)\s", l) for l in block):
        return True
    hits = sum(1 for l in block if CODEISH_RE.search(l))
    return hits * 5 >= len(block) * 3   # ≥60% 行帶 code 符號才算；散文偶有分號不會誤判


def render_block(block: list[str]) -> str:
    """縮排區塊 → <pre> 或 <p>。"""
    if codeish(block):
        ind = min(indent_of(l) for l in block)
        return "<pre>" + html.escape("\n".join(l[ind:] for l in block), quote=False) + "</pre>"
    return "<p>" + inline(" ".join(l.strip() for l in block)) + "</p>"


def take_indented(lines: list[str], i: int) -> tuple[list[str], int]:
    block = []
    while i < len(lines) and lines[i].strip() and indent_of(lines[i]) >= 2 and not LIST_RE.match(lines[i]) \
            or (i < len(lines) and lines[i].strip() and indent_of(lines[i]) >= 2 and LIST_RE.match(lines[i])
                and len(LIST_RE.match(lines[i]).group(1)) >= 2 and codeish([lines[i]])):
        block.append(lines[i])
        i += 1
    return block, i


def next_nonblank(lines: list[str], i: int) -> int:
    while i < len(lines) and not lines[i].strip():
        i += 1
    return i


def convert(body: str) -> str:
    lines = body.split("\n")
    out: list[str] = []
    para: list[str] = []
    i, n = 0, len(lines)

    def flush_para():
        nonlocal para
        if para:
            out.append("<p>" + inline(" ".join(x.strip() for x in para)) + "</p>")
            para = []

    while i < n:
        line = lines[i]
        stripped = line.strip()
        if not stripped:
            flush_para()
            i += 1
            continue
        nxt = lines[i + 1].strip() if i + 1 < n else ""
        if FIELD_RE.match(line):
            flush_para()
            out.append("<p>" + inline(stripped) + "</p>")
            i += 1
            continue
        if UNDERLINE_RE.match(nxt) and indent_of(line) == 0:
            flush_para()
            out.append("<p><strong>" + inline(stripped) + "</strong></p>")
            i += 2
            continue
        if stripped.startswith("|") and stripped.endswith("|"):
            flush_para()
            rows = []
            while i < n and lines[i].strip().startswith("|"):
                cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
                if not all(re.fullmatch(r":?-{2,}:?", c) for c in cells):
                    rows.append(cells)
                i += 1
            if rows:
                head, *rest = rows
                t = ["<table><thead><tr>" + "".join(f"<th>{inline(c)}</th>" for c in head) + "</tr></thead><tbody>"]
                for r in rest:
                    t.append("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in r) + "</tr>")
                t.append("</tbody></table>")
                out.append("".join(t))
            continue
        m = LIST_RE.match(line)
        if m and len(m.group(1)) <= 4:
            flush_para()
            ordered = m.group(2) not in ("-", "*")
            items: list[str] = []
            while i < n:
                m2 = LIST_RE.match(lines[i]) if i < n else None
                if not (m2 and len(m2.group(1)) <= 4 and ((m2.group(2) not in ("-", "*")) == ordered)):
                    break
                parts = [inline(m2.group(3).strip())]
                item_indent = len(m2.group(1))
                i += 1
                # 項目本體的緊接續行（無空行、縮排比項目深、不是新清單項）：一律散文
                cont = []
                while i < n and lines[i].strip() and indent_of(lines[i]) > item_indent \
                        and not LIST_RE.match(lines[i]) \
                        and not (indent_of(lines[i]) >= item_indent + 4 and CODEISH_RE.search(lines[i])):
                    cont.append(lines[i])
                    i += 1
                if cont:
                    # 緊接的續行一律是散文（草稿裡的 code 都用空行隔開）
                    parts[0] += " " + inline(" ".join(l.strip() for l in cont))
                # 之後屬於同一項的縮排區塊（可跨空行）
                while True:
                    j = next_nonblank(lines, i)
                    if j < n and indent_of(lines[j]) >= 2 and not (LIST_RE.match(lines[j]) and len(LIST_RE.match(lines[j]).group(1)) <= 4):
                        block, i2 = take_indented(lines, j)
                        if not block:
                            break
                        i = i2
                        parts.append(render_block(block))
                        continue
                    break
                items.append("<li>" + "".join(parts) + "</li>")
                # 空行後若接同型清單項則繼續
                j = next_nonblank(lines, i)
                m3 = LIST_RE.match(lines[j]) if j < n else None
                if m3 and len(m3.group(1)) <= 4 and ((m3.group(2) not in ("-", "*")) == ordered):
                    i = j
                    continue
                break
            out.append(("<ol>" if ordered else "<ul>") + "".join(items) + ("</ol>" if ordered else "</ul>"))
            continue
        if indent_of(line) >= 2:
            flush_para()
            block, i2 = take_indented(lines, i)
            if not block:          # 防呆：吃不進去就當一般段落行，絕不原地打轉
                para.append(line)
                i += 1
                continue
            i = i2
            out.append(render_block(block))
            continue
        para.append(line)
        i += 1
    flush_para()
    return "\n".join(out)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    count = 0
    for prefix, path in SRC.items():
        text = path.read_text(encoding="utf-8")
        blocks = re.split(r"^## (R\d+|C\d+)\. ", text, flags=re.M)
        for k in range(1, len(blocks), 2):
            rid, rest = blocks[k], blocks[k + 1]
            name = (prefix + "-" + rid) if rid.startswith("R") else rid
            mt = re.search(r"^### Title\s*\n\s*`?(\[42[^\n`]*)`?\s*$", rest, flags=re.M)
            mb = re.search(r"^### Body[^\n]*\n\s*```text\n(.*?)\n```", rest, flags=re.M | re.S)
            if not mt or not mb:
                print(f"skip {name}: title/body not found", file=sys.stderr)
                continue
            title = mt.group(1).strip().strip("`")
            body_html = convert(expand_paste(mb.group(1)))
            doc = (
                "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + html.escape(title) + "</title>"
                "<style>body{font-family:sans-serif;max-width:900px;margin:2em auto;line-height:1.45}"
                "pre{background:#f4f4f4;padding:.6em;overflow:auto}table{border-collapse:collapse}"
                "td,th{border:1px solid #999;padding:.2em .5em}li{margin:.3em 0}</style></head><body>\n"
                "<p><em>Title (copy into the Title field):</em> " + html.escape(title) + "</p><hr>\n"
                + body_html + "\n</body></html>\n"
            )
            (OUT / f"{name}.html").write_text(doc, encoding="utf-8", newline="\n")
            count += 1
            print(f"{name}.html  ({len(body_html)} chars)")
    print(f"wrote {count} files to {OUT}")


if __name__ == "__main__":
    main()
