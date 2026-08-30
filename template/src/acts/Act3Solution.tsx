import { Sequence, interpolate, useCurrentFrame } from "remotion";
import { COLORS, TRANSITION_F } from "../engine/constants";
import type { Layout } from "../engine/layout";
import type { ContentJson } from "../engine/types";
import type { SceneWindow, Timeline } from "../engine/timeline";
import { StepCard } from "./components/StepCard";
import { DerivationPopup } from "./components/DerivationPopup";
import { PitfallCard } from "./components/PitfallCard";
import { ChecklistCard } from "./components/ChecklistCard";
import { Katex } from "./components/Katex";
import { useEnter } from "./components/anim";

export interface Act3Group {
  scenes: SceneWindow[]; start: number; end: number; stepRef?: number; hasStepCard: boolean;
}
export const buildGroups = (timeline: Timeline): Act3Group[] => {
  const groups: Act3Group[] = [];
  for (const w of timeline.scenes.filter((s) => s.act === 3)) {
    const last = groups[groups.length - 1];
    const continuable = last && last.stepRef != null && last.stepRef === w.props.stepRef
      && (w.component === "step-card" || w.component === "derivation-popup");
    if (continuable) {
      last.scenes.push(w);
      last.end = w.startFrame + w.durFrames;
    } else {
      groups.push({ scenes: [w], start: w.startFrame, end: w.startFrame + w.durFrames,
        stepRef: (w.component === "step-card" || w.component === "derivation-popup") ? w.props.stepRef : undefined,
        hasStepCard: w.component === "step-card" });
    }
  }
  return groups;
};

const ConclusionBar: React.FC<{ formula: string; span: number }> = ({ formula, span }) => {
  const f = useCurrentFrame();
  const enter = useEnter(0);
  const exit = interpolate(f, [span - TRANSITION_F, span], [1, 0],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return (
    <div style={{ opacity: enter.opacity * exit, transform: `translateY(${enter.y}px)` }}>
      <div style={{ display: "flex", alignItems: "center", gap: 20, backgroundColor: COLORS.card,
        border: `2px solid ${COLORS.ok}`, borderRadius: 18, padding: "16px 28px" }}>
        <span style={{ fontSize: 38, color: COLORS.ok, fontWeight: 800 }}>✓ 结论</span>
        <Katex tex={formula} fontSize={44} color={COLORS.text} />
      </div>
    </div>
  );
};

export const Act3Solution: React.FC<{ content: ContentJson; timeline: Timeline; layout: Layout }> =
({ content, timeline, layout }) => {
  const groups = buildGroups(timeline);
  const stepGroups = groups.filter((g) => g.hasStepCard);
  const lastStep = stepGroups[stepGroups.length - 1];
  const afterStepScenes = groups.filter((g) => !g.hasStepCard && (!lastStep || g.start >= lastStep.end));
  const needOffset = lastStep && afterStepScenes.length > 0;
  const total = content.steps.length;
  return (
    <>
      <div style={{ position: "absolute", left: layout.main.x, top: layout.main.y,
        width: layout.main.w, height: layout.main.h }}>
        {lastStep && (
          <Sequence from={lastStep.end} durationInFrames={timeline.act4StartFrame - lastStep.end}>
            <ConclusionBar formula={content.steps[lastStep.stepRef! - 1].derivation}
              span={timeline.act4StartFrame - lastStep.end} />
          </Sequence>
        )}
        <div style={needOffset ? { position: "relative", top: 220 } : undefined}>
          {groups.map((g) => {
            const span = g.end - g.start;
            const head = g.scenes[0];
            return (
              <Sequence key={g.start} from={g.start} durationInFrames={span}>
                {g.hasStepCard && (
                  <StepCard step={content.steps[g.stepRef! - 1]} index={g.stepRef!} total={total} durFrames={span} />
                )}
                {g.scenes.filter((w) => w.component === "derivation-popup").map((w) => (
                  <Sequence key={w.sceneId} from={w.startFrame - g.start} durationInFrames={w.durFrames}>
                    <DerivationPopup formula={w.props.formula!}
                      note={content.steps[w.props.stepRef! - 1].note} durFrames={w.durFrames} />
                  </Sequence>
                ))}
                {head.component === "pitfall-card" && (
                  <PitfallCard p={content.pitfalls[head.props.pitfallRef! - 1]} durFrames={span} />
                )}
                {head.component === "checklist-card" && (
                  <ChecklistCard pitfalls={head.props.pitfallRefs!.map((r) => content.pitfalls[r - 1])}
                    durFrames={span}
                    availWidth={layout.main.w} availHeight={layout.main.h - (needOffset ? 220 : 0)} />
                )}
              </Sequence>
            );
          })}
        </div>
      </div>
    </>
  );
};
