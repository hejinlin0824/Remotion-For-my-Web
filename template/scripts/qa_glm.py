# -*- coding: utf-8 -*-
"""
qa_glm.py — GLM-5.3-flash 客观审帧（重叠/乱码/黑字/越界/公式崩坏，不审审美）
用法: python scripts/qa_glm.py [png ...]   （缺省审 out/qa/ 全部 png）
Key: ZHIPU_API_KEY / ZHIPUAI_API_KEY / GLM_API_KEY 环境变量，回落 ~/.claude/settings.json 的 env.ANTHROPIC_AUTH_TOKEN
并发: ThreadPoolExecutor 并发审帧，QA_GLM_CONCURRENCY 可调（默认 4）；prompt/FAIL 标准/report 格式/exit 语义与串行版一致，
      report.md 仍按帧序输出（结果收集后按序写），每帧独立重试退避、429/超时按帧隔离（单帧最终失败 → 汇总 exit 1）。
输出: out/qa/report.md；任一 FAIL → exit 1
归因: 单帧最终失败（重试尽/非 200，非模型判负）时另写独立行 `ERROR <帧名>\t<摘要≤120字符>`（摘要含异常类或
      退避后状态码），与模型 FAIL 行明确区分——并行化把错误细节隔离在进程内，report.md 是下游唯一归因面（F3-R2）。
"""
import base64, json, mimetypes, os, pathlib, sys, time
from concurrent.futures import ThreadPoolExecutor, as_completed
import requests

API_URL = "https://open.bigmodel.cn/api/anthropic/v1/messages"  # Anthropic 兼容端点
MODEL = "glm-5.3-flash"
PROMPT = (
    "这是讲题教学视频的一帧渲染截图（深色科技风正文，或纯白片尾）。只审客观项，不评审美。\n"
    "以下红线任一成立即 FAIL：\n"
    "1) 乱码、方框缺字、LaTeX 源码裸露、符号错位堆叠；\n"
    "2) 公式、数字、符号的任何错误或与题面不符（这是知识性错误——公式/符号/逻辑与题面不符，不是错字）；\n"
    "3) 文字/卡片/公式相互重叠、遮挡、溢出画面边界、换行错位等排版崩坏；\n"
    "4) 黑底黑字/白底白字等对比度失效（文字不可见）。\n"
    "放行项：散文性说明文字中不影响题意的无害笔误（多字/少字/别字/重复字）不判 FAIL；"
    "若整帧仅存在此类问题则判 PASS，且理由中须注明「含无害错字」。\n"
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

def concurrency():
    try:
        return max(1, int(os.environ.get("QA_GLM_CONCURRENCY", "4").strip()))
    except ValueError:
        return 4

def check(key, path):
    mime = mimetypes.guess_type(str(path))[0] or "image/png"
    b64 = base64.b64encode(path.read_bytes()).decode()
    # max_tokens 4096：glm-5.3-flash 的 thinking 会占用预算，1024 常导致正文为空（空响应≠视觉缺陷）
    body = {"model": MODEL, "max_tokens": 4096, "messages": [{"role": "user", "content": [
        {"type": "image", "source": {"type": "base64", "media_type": mime, "data": b64}},
        {"type": "text", "text": PROMPT}]}]}
    cause = "重试耗尽"  # 最后一次失败的归因（退避后状态码/异常类），进最终错误摘要
    for attempt in range(5):
        try:
            r = requests.post(API_URL, headers={
                "x-api-key": key, "Authorization": f"Bearer {key}",
                "anthropic-version": "2023-06-01", "Content-Type": "application/json"},
                json=body, timeout=180)
            if r.status_code == 429 or r.status_code >= 500:
                cause = f"HTTP {r.status_code}"
                time.sleep(20 * (attempt + 1)); continue
            if r.status_code != 200:
                return f"[error] {r.status_code} {r.text[:200]}", False
            text = "".join(p.get("text", "") for p in r.json().get("content", []) if p.get("type") == "text").strip()
            if not text:
                cause = "空正文"; time.sleep(8); continue  # 200 但正文为空（thinking 吃满预算）→ 瞬态错误重试
            ok = "PASS" in text.splitlines()[-1].upper()
            return text, ok
        except Exception as e:
            cause = f"{type(e).__name__}: {e}"
            time.sleep(5)
    return f"[error] 重试耗尽（{cause}）", False

def audit(key, indexed):
    i, p = indexed
    text, ok = check(key, p)
    time.sleep(2)  # 帧间节流（串行版语义保留：每 worker 出帧后停 2s，整体速率 ≈ 并发度/2s）
    return i, p, text, ok

def main():
    key = load_key()
    root = pathlib.Path(__file__).resolve().parent.parent
    targets = [pathlib.Path(a) for a in sys.argv[1:]] or sorted((root/"out"/"qa").glob("*.png"))
    targets = [t for t in targets if t.suffix == ".png" and t.name != "scaffold.png"]
    if not targets:
        sys.exit("[fatal] 没有待审帧")
    fails, lines, done = [], [f"# GLM 审帧报告 — {MODEL}\n"], {}
    with ThreadPoolExecutor(max_workers=concurrency()) as pool:
        futures = [pool.submit(audit, key, item) for item in enumerate(targets)]
        for fut in as_completed(futures):
            i, p, text, ok = fut.result()
            done[i] = (p, text, ok)
            print(f"[{'PASS' if ok else 'FAIL'}] {p.name}")
    for p, text, ok in (done[i] for i in range(len(targets))):  # report.md 按帧序写
        lines.append(f"## {p.name}\n\n{text}\n")
        if not ok:
            fails.append(p.name)
            if text.startswith("[error]"):  # 单帧最终失败（异常/非 200，非模型判负）→ 独立错误行，供下游逐帧归因
                lines.append(f"ERROR {p.name}\t{text.removeprefix('[error] ').strip()[:120]}\n")
    out = root/"out"/"qa"/"report.md"
    if out.exists():
        out.replace(out.with_name("report_prev.md"))  # 写前备份上一轮报告
    out.write_text("\n".join(lines), encoding="utf-8")
    print(f"\n报告: {out}；FAIL {len(fails)}/{len(targets)}")
    sys.exit(1 if fails else 0)

if __name__ == "__main__":
    main()
