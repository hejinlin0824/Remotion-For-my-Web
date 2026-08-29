# -*- coding: utf-8 -*-
"""
qa_glm.py — GLM-5.3-flash 客观审帧（重叠/乱码/黑字/越界/公式崩坏，不审审美）
用法: python scripts/qa_glm.py [png ...]   （缺省审 out/qa/ 全部 png）
Key: ZHIPU_API_KEY / ZHIPUAI_API_KEY / GLM_API_KEY 环境变量，回落 ~/.claude/settings.json 的 env.ANTHROPIC_AUTH_TOKEN
输出: out/qa/report.md；任一 FAIL → exit 1
"""
import base64, json, mimetypes, os, pathlib, sys, time
import requests

API_URL = "https://open.bigmodel.cn/api/anthropic/v1/messages"  # Anthropic 兼容端点
MODEL = "glm-5.3-flash"
PROMPT = (
    "这是讲题教学视频的一帧渲染截图（深色科技风正文，或纯白片尾）。只审客观项，不评审美：\n"
    "1) 文字/卡片/公式是否相互重叠、遮挡、溢出画面边界；\n"
    "2) 是否有乱码、方框缺字、明显错误字符；\n"
    "3) 是否有黑底黑字/白底白字等对比度失效（文字不可见）；\n"
    "4) 数学公式是否渲染崩坏（LaTeX 源码裸露、符号错位堆叠）。\n"
    "逐项简答，最后一行输出：PASS 或 FAIL（一句话理由）。"
)

def load_key():
    for k in ("ZHIPU_API_KEY", "ZHIPUAI_API_KEY", "GLM_API_KEY"):
        if os.environ.get(k):
            return os.environ[k].strip()
    settings = pathlib.Path.home() / ".claude" / "settings.json"
    if settings.exists():
        d = json.loads(settings.read_text(encoding="utf-8"))
        tok = d.get("env", {}).get("ANTHROPIC_AUTH_TOKEN")
        if tok:
            return tok.strip()
    sys.exit("[fatal] 未找到 GLM Key")

def check(key, path):
    mime = mimetypes.guess_type(str(path))[0] or "image/png"
    b64 = base64.b64encode(path.read_bytes()).decode()
    # max_tokens 4096：glm-5.3-flash 的 thinking 会占用预算，1024 常导致正文为空（空响应≠视觉缺陷）
    body = {"model": MODEL, "max_tokens": 4096, "messages": [{"role": "user", "content": [
        {"type": "image", "source": {"type": "base64", "media_type": mime, "data": b64}},
        {"type": "text", "text": PROMPT}]}]}
    for attempt in range(5):
        try:
            r = requests.post(API_URL, headers={
                "x-api-key": key, "Authorization": f"Bearer {key}",
                "anthropic-version": "2023-06-01", "Content-Type": "application/json"},
                json=body, timeout=180)
            if r.status_code == 429 or r.status_code >= 500:
                time.sleep(20 * (attempt + 1)); continue
            if r.status_code != 200:
                return f"[error] {r.status_code} {r.text[:200]}", False
            text = "".join(p.get("text", "") for p in r.json().get("content", []) if p.get("type") == "text").strip()
            if not text:
                time.sleep(8); continue  # 200 但正文为空（thinking 吃满预算）→ 视为瞬态错误重试
            ok = "PASS" in text.splitlines()[-1].upper()
            return text, ok
        except Exception:
            time.sleep(5)
    return "[error] 重试耗尽", False

def main():
    key = load_key()
    root = pathlib.Path(__file__).resolve().parent.parent
    targets = [pathlib.Path(a) for a in sys.argv[1:]] or sorted((root/"out"/"qa").glob("*.png"))
    targets = [t for t in targets if t.suffix == ".png" and t.name != "scaffold.png"]
    if not targets:
        sys.exit("[fatal] 没有待审帧")
    fails, lines = [], [f"# GLM 审帧报告 — {MODEL}\n"]
    for p in targets:
        text, ok = check(key, p)
        print(f"[{'PASS' if ok else 'FAIL'}] {p.name}")
        lines.append(f"## {p.name}\n\n{text}\n")
        if not ok:
            fails.append(p.name)
        time.sleep(2)
    out = root/"out"/"qa"/"report.md"
    if out.exists():
        out.replace(out.with_name("report_prev.md"))  # 写前备份上一轮报告
    out.write_text("\n".join(lines), encoding="utf-8")
    print(f"\n报告: {out}；FAIL {len(fails)}/{len(targets)}")
    sys.exit(1 if fails else 0)

if __name__ == "__main__":
    main()
