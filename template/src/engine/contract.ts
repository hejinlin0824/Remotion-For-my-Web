import type { AudioMeta, ContentJson, Scene } from "./types";

const ACT_COMPONENTS: Record<number, string[]> = {
  2: ["problem-card", "knowledge-card"],
  3: ["step-card", "derivation-popup", "pitfall-card", "checklist-card"],
  4: ["general-list"],
};
const ref = (p: unknown) => typeof p === "number" && Number.isInteger(p) && p >= 1;

export function validateContract(content: ContentJson, audio: AudioMeta): string[] {
  const errs: string[] = [];
  const lineIds = new Set(content.problem.lines.map((l) => l.id));
  content.scenes.forEach((s: Scene, i) => {
    const tag = `scenes[${i}](${s.id})`;
    if (![2, 3, 4].includes(s.act)) errs.push(`${tag}: act 只允许 2/3/4`);
    if (!(ACT_COMPONENTS[s.act] ?? []).includes(s.component)) {
      errs.push(`${tag}: 组件 ${s.component} 不允许出现在 act${s.act}`);
    }
    if (!s.ttsText?.trim()) errs.push(`${tag}: ttsText 为空`);
    if (!audio.lines.some((l) => l.sceneId === s.id)) errs.push(`${tag}: 无对应音频行`);
    const p = s.props ?? {};
    if (s.component === "problem-card" && (i !== 0 || s.act !== 2)) errs.push(`${tag}: problem-card 必须是 act2 第一场`);
    if (s.component === "knowledge-card" && (!ref(p.knowledgeRef) || p.knowledgeRef! > content.knowledge.length))
      errs.push(`${tag}: knowledgeRef 越界`);
    if ((s.component === "step-card" || s.component === "derivation-popup")) {
      if (!ref(p.stepRef) || p.stepRef! > content.steps.length) errs.push(`${tag}: stepRef 越界`);
    }
    if (s.component === "derivation-popup" && !p.formula?.trim()) errs.push(`${tag}: 缺 formula`);
    if (s.component === "pitfall-card" && (!ref(p.pitfallRef) || p.pitfallRef! > content.pitfalls.length))
      errs.push(`${tag}: pitfallRef 越界`);
    if (s.component === "checklist-card") {
      const ok = Array.isArray(p.pitfallRefs) && p.pitfallRefs.length > 0
        && p.pitfallRefs!.every((r) => ref(r) && r <= content.pitfalls.length);
      if (!ok) errs.push(`${tag}: pitfallRefs 非法`);
    }
    if (s.component === "general-list" && (!ref(p.itemRef) || p.itemRef! > content.generalMethod.length))
      errs.push(`${tag}: itemRef 越界`);
  });
  content.steps.forEach((st, i) => {
    if (!lineIds.has(st.usesAnchor)) errs.push(`steps[${i}].usesAnchor "${st.usesAnchor}" 不存在于 problem.lines`);
  });
  return errs;
}
